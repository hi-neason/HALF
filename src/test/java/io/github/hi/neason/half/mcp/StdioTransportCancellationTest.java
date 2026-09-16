package io.github.hi.neason.half.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StdioTransportCancellationTest {
    @Test
    @Timeout(10)
    void cancellingAnUnwrittenRequestTerminatesThePipeAndChild() throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                BlockedServer.class.getName());
        var ready = new CompletableFuture<Long>();
        var failure = new CompletableFuture<McpException>();
        try (var transport = new StdioMcpTransport(command, null, Map.of(), Duration.ofSeconds(30))) {
            transport.start(message -> ready.complete(message.path("params").path("pid").asLong()),
                    (id, error) -> failure.complete(error));
            long pid = ready.get(5, TimeUnit.SECONDS);
            var process = ProcessHandle.of(pid).orElseThrow();
            var request = McpJson.object().put("jsonrpc", "2.0").put("id", "one").put("method", "tools/call");
            request.putObject("params").put("payload", "x".repeat(512 * 1024));
            var written = transport.send(request);
            assertFalse(written.isDone(), "The child never consumes stdin, so a large write must remain pending");
            transport.cancelRequest("one");
            assertNotNull(failure.get(3, TimeUnit.SECONDS));
            assertTrue(written.isCompletedExceptionally());
            process.onExit().get(3, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertTrue(transport.send(request).isCompletedExceptionally());
        }
    }

    public static final class BlockedServer {
        public static void main(String[] args) throws InterruptedException {
            var ready = McpJson.object().put("jsonrpc", "2.0").put("method", "ready");
            ready.putObject("params").put("pid", ProcessHandle.current().pid());
            System.out.println(ready);
            System.out.flush();
            new CountDownLatch(1).await();
        }
    }
}
