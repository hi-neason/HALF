package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(15)
class McpConnectionTest {
    @Test
    void malformedJsonRetainsSafeReasonWithoutPayloadOrCause() throws Exception {
        for (String scenario : List.of("json", "duplicate", "trailing")) {
            assertFailure(scenario, "Invalid MCP JSON message");
        }
    }

    @Test
    void invalidEnvelopeAndUtf8HaveDistinctSafeReasons() throws Exception {
        assertFailure("envelope", "Invalid MCP JSON-RPC message");
        assertFailure("utf8", "Invalid MCP UTF-8 message");
    }

    @Test
    void rejectsOversizedFrameBeforeItsNewline() throws Exception {
        assertFailure("oversize", "MCP message exceeds size limit");
    }

    @Test
    void distinguishesClosedOutputAndTruncatedFrame() throws Exception {
        assertFailure("eof", "MCP server closed its output");
        assertFailure("partial", "Incomplete MCP message at end of stream");
    }

    private void assertFailure(String scenario, String message) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = List.of(java, "-cp", System.getProperty("java.class.path"),
                BrokenServer.class.getName(), scenario);
        try (var connection = new McpConnection(command, null, Map.of(), Duration.ofSeconds(5))) {
            var error = assertThrows(McpException.class,
                    () -> connection.request("ping", new ObjectMapper().createObjectNode(), null));
            assertEquals(message, error.getMessage());
            assertNull(error.getCause());
            assertFalse(error.toString().contains("private-secret"));
        }
    }

    /** Child process waits for a request before emitting one malformed frame. */
    public static final class BrokenServer {
        public static void main(String[] args) throws Exception {
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            input.readLine();
            switch (args[0]) {
                case "json" -> System.out.print("{private-secret\n");
                case "duplicate" -> System.out.print("{\"id\":\"private-secret\",\"id\":\"1\"}\n");
                case "trailing" -> System.out.print("{} {\"private-secret\":true}\n");
                case "envelope" -> System.out.print("{\"jsonrpc\":\"private-secret\"}\n");
                case "utf8" -> System.out.write(new byte[]{(byte) 0xc3, (byte) 0x28, '\n'});
                case "oversize" -> System.out.print("x".repeat(1024 * 1024 + 1));
                case "partial" -> System.out.print("{\"private-secret\":true");
                case "eof" -> { }
                default -> throw new IllegalArgumentException("Unknown fixture scenario");
            }
            System.out.flush();
            if (args[0].equals("eof") || args[0].equals("partial")) System.out.close();
            while (input.readLine() != null) { }
        }
    }
}
