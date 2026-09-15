package io.github.hi.neason.half.model.openai.api;

import io.github.hi.neason.half.model.ModelHttpException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 单订阅虚拟线程：按需求解析帧，不预先建立无界事件队列。 */
final class OpenAiRawStream implements Flow.Subscription, Runnable {
    private static final int MAX_FRAME_CHARS = 4 * 1024 * 1024;
    private final OpenAiApiClient client;
    private final HttpRequest request;
    private final Flow.Subscriber<? super OpenAiSseEvent> subscriber;
    private final boolean responses;
    private final Thread worker;
    private long demand;
    private boolean cancelled;
    private boolean finished;
    private Throwable failure;
    private InputStream body;

    OpenAiRawStream(OpenAiApiClient client, HttpRequest request,
                    Flow.Subscriber<? super OpenAiSseEvent> subscriber, boolean responses) {
        this.client = client;
        this.request = request;
        this.subscriber = subscriber;
        this.responses = responses;
        worker = Thread.ofVirtual().name("half-api-stream").unstarted(this);
    }

    void start() { worker.start(); }

    @Override public synchronized void request(long n) {
        if (cancelled || finished) return;
        if (n <= 0) { fail(new IllegalArgumentException("request(n) requires n > 0")); return; }
        demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
        notifyAll();
    }

    @Override public void cancel() {
        InputStream input;
        synchronized (this) {
            if (cancelled || finished) return;
            cancelled = true;
            input = body;
            notifyAll();
        }
        worker.interrupt();
        OpenAiApiClient.closeBody(input);
        client.streams.remove(this);
    }

    void fail(Throwable error) {
        InputStream input;
        synchronized (this) {
            if (cancelled || finished || failure != null) return;
            failure = error;
            input = body;
            notifyAll();
        }
        worker.interrupt();
        OpenAiApiClient.closeBody(input);
    }

    private synchronized boolean awaitDemand() throws InterruptedException {
        while (demand == 0 && !cancelled && failure == null) wait();
        return !cancelled && failure == null;
    }

    @Override public void run() {
        ScheduledFuture<?> deadline = null;
        try {
            if (client.closed) fail(new IOException("Client is closed"));
            if (!awaitDemand()) return;
            deadline = client.timer.schedule(() -> fail(new HttpTimeoutException("API stream timed out")),
                    client.timeout.toNanos(), TimeUnit.NANOSECONDS);
            var response = client.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            synchronized (this) {
                body = response.body();
                if (cancelled || failure != null) return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ModelHttpException(response.statusCode());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.split(";", 2)[0].trim().equalsIgnoreCase("text/event-stream"))
                throw new IOException("Expected text/event-stream response");
            Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT));
            var parser = new FrameReader(reader);
            boolean terminal = false;
            while (awaitDemand()) {
                OpenAiSseEvent event = parser.next();
                if (event == null) throw new IOException("SSE ended before terminal event");
                if (event.data().equals("[DONE]")) {
                    if (responses) throw new IOException("Unexpected Responses terminal marker");
                    terminal = true;
                    break;
                }
                var json = event.json();
                if (!json.isObject()) throw new IOException("Expected SSE JSON object");
                String type = json.path("type").asText("");
                terminal = responses && (type.equals("response.completed") || type.equals("response.failed")
                        || type.equals("response.incomplete") || type.equals("error"));
                synchronized (this) {
                    if (cancelled || failure != null) break;
                    if (demand != Long.MAX_VALUE) demand--;
                }
                try { subscriber.onNext(event); }
                catch (Throwable error) { cancel(); return; }
                if (terminal) break;
            }
            synchronized (this) {
                if (terminal && !cancelled && failure == null) finished = true;
            }
            if (finished) try { subscriber.onComplete(); } catch (Throwable ignored) { }
        } catch (Throwable error) {
            synchronized (this) { if (failure == null) failure = error; }
        } finally {
            if (deadline != null) deadline.cancel(false);
            OpenAiApiClient.closeBody(body);
            client.streams.remove(this);
            Throwable error;
            synchronized (this) {
                error = cancelled || finished ? null : failure;
                finished = true;
            }
            if (error != null) try { subscriber.onError(error); } catch (Throwable ignored) { }
        }
    }

    /** 按字符限制整帧大小，避免 readLine 在恶意长行上先分配无界字符串。 */
    private static final class FrameReader {
        private final Reader reader;
        private boolean first = true;
        private boolean afterCr;
        FrameReader(Reader reader) { this.reader = reader; }

        OpenAiSseEvent next() throws IOException {
            StringBuilder data = new StringBuilder();
            StringBuilder line = new StringBuilder();
            String event = "message";
            boolean hasData = false;
            int count = 0;
            for (;;) {
                int value = reader.read();
                if (value < 0) return null;
                if (first) { first = false; if (value == '\uFEFF') continue; }
                if (afterCr) { afterCr = false; if (value == '\n') continue; }
                if (++count > MAX_FRAME_CHARS) throw new IOException("SSE frame exceeds character limit");
                if (value != '\n' && value != '\r') { line.append((char) value); continue; }
                afterCr = value == '\r';
                if (line.isEmpty()) {
                    if (hasData) return new OpenAiSseEvent(event, data.toString());
                    event = "message";
                    count = 0;
                    continue;
                }
                int colon = line.indexOf(":");
                String field = colon < 0 ? line.toString() : line.substring(0, colon);
                String fieldValue = colon < 0 ? "" : line.substring(colon + 1);
                if (fieldValue.startsWith(" ")) fieldValue = fieldValue.substring(1);
                if (field.equals("event")) event = fieldValue.isEmpty() ? "message" : fieldValue;
                if (field.equals("data")) {
                    if (hasData) data.append('\n');
                    data.append(fieldValue);
                    hasData = true;
                }
                line.setLength(0);
            }
        }
    }
}
