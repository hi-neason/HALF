package io.github.hi.neason.half.model.http;

import com.sun.net.httpserver.HttpServer;
import io.github.hi.neason.half.model.ModelHttpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.*;

class HttpCallsTest {
    private HttpServer server;
    private HttpClient client;
    private ExecutorService executor;
    private HttpRequest request;
    private final CountDownLatch bodySent = new CountDownLatch(1);
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private volatile boolean holdBody;
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("private response body".getBytes(StandardCharsets.UTF_8));
                out.flush();
                bodySent.countDown();
                if (holdBody) {
                    try { releaseBody.await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
            }
        });
        server.start();
        client = HttpClient.newHttpClient();
        request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
    }

    @AfterEach
    void stop() {
        releaseBody.countDown();
        server.stop(0);
        client.shutdownNow();
        executor.shutdownNow();
    }

    @Test
    void returnsCompleteBodyAndPreservesSafeHttpStatus() throws Exception {
        try (var calls = new HttpCalls(client, Duration.ofSeconds(3))) {
            assertEquals("private response body", calls.send(request).body());
            status = 429;
            var error = assertThrows(ModelHttpException.class, () -> calls.send(request));
            assertEquals(429, error.statusCode());
            assertFalse(error.toString().contains("private"));
            assertNull(error.getCause());
        }
    }

    @Test
    void deadlineIncludesBodyAfterHeadersArrive() throws Exception {
        holdBody = true;
        try (var calls = new HttpCalls(client, Duration.ofMillis(400))) {
            var pending = executor.submit(() -> calls.send(request));
            assertTrue(bodySent.await(3, TimeUnit.SECONDS));
            var error = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, error.getCause());
        }
    }

    @SuppressWarnings("try")
    @Test
    void closeCancelsInFlightBodyAndRejectsLaterCalls() throws Exception {
        holdBody = true;
        try (var calls = new HttpCalls(client, Duration.ofSeconds(10))) {
            var pending = executor.submit(() -> calls.send(request));
            assertTrue(bodySent.await(3, TimeUnit.SECONDS));
            calls.close();
            var error = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, error.getCause());
            assertThrows(IOException.class, () -> calls.send(request));
            calls.close();
        }
    }

    @Test
    void interruptionIsPropagatedAndDoesNotCloseClient() throws Exception {
        holdBody = true;
        try (var calls = new HttpCalls(client, Duration.ofSeconds(10))) {
            var failure = new java.util.concurrent.CompletableFuture<Throwable>();
            var thread = Thread.ofVirtual().start(() -> {
                try { calls.send(request); failure.complete(null); }
                catch (Throwable error) { failure.complete(error); }
            });
            assertTrue(bodySent.await(3, TimeUnit.SECONDS));
            thread.interrupt();
            assertInstanceOf(InterruptedException.class, failure.get(3, TimeUnit.SECONDS));
            releaseBody.countDown();
            assertEquals(200, calls.send(request).statusCode());
        }
    }
    @Test
    void unexpectedAsyncFailureReportsOnlyItsType() {
        var original = new IllegalStateException("private credential and response body");
        try (var calls = new HttpCalls(new FailedClient(original), Duration.ofSeconds(3))) {
            var error = assertThrows(IOException.class, () -> calls.send(request));
            assertEquals("HTTP request failed: IllegalStateException", error.getMessage());
            assertFalse(error.toString().contains(original.getMessage()));
            assertNull(error.getCause());
            assertEquals(0, error.getSuppressed().length);
        }
    }

    @Test
    void asyncIoFailureIsPassedThroughUnchanged() {
        var original = new HttpTimeoutException("original timeout");
        try (var calls = new HttpCalls(new FailedClient(original), Duration.ofSeconds(3))) {
            assertSame(original, assertThrows(HttpTimeoutException.class, () -> calls.send(request)));
        }
    }

    private static final class FailedClient extends HttpClient {
        private final Throwable failure;

        private FailedClient(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                               HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                               HttpResponse.BodyHandler<T> handler,
                                                               HttpResponse.PushPromiseHandler<T> pushHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<CookieHandler> cookieHandler() { throw new UnsupportedOperationException(); }
        @Override public Optional<Duration> connectTimeout() { throw new UnsupportedOperationException(); }
        @Override public Redirect followRedirects() { throw new UnsupportedOperationException(); }
        @Override public Optional<ProxySelector> proxy() { throw new UnsupportedOperationException(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { throw new UnsupportedOperationException(); }
        @Override public Optional<Authenticator> authenticator() { throw new UnsupportedOperationException(); }
        @Override public Version version() { throw new UnsupportedOperationException(); }
        @Override public Optional<Executor> executor() { throw new UnsupportedOperationException(); }
    }

}
