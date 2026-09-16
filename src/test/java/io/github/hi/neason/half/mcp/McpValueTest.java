package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpValueTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void resultRequiresTypedProtocolFields() throws Exception {
        for (String invalid : List.of("{}", "{\"content\":{}}", "{\"content\":[],\"isError\":\"true\"}",
                "{\"content\":[],\"structuredContent\":[]}", "{\"content\":[{}]}",
                "{\"content\":[{\"type\":\"text\"}]}")) {
            var value = (ObjectNode) json.readTree(invalid);
            assertThrows(IllegalArgumentException.class, () -> new McpCallResult(value));
        }
    }

    @Test
    void structuredOnlyResultIsValidAndSnapshotIsImmutable() throws Exception {
        var source = (ObjectNode) json.readTree("{\"content\":[],\"structuredContent\":{\"value\":42}}");
        var result = new McpCallResult(source);
        source.removeAll();
        result.value().removeAll();
        assertFalse(result.isError());
        assertEquals("{\"value\":42}", result.text());
        assertEquals(42, result.value().path("structuredContent").path("value").asInt());
    }

    @Test
    void toolRetainsTaskAndOutputMetadataAsSnapshots() throws Exception {
        var metadata = (ObjectNode) json.readTree("""
                {"execution":{"taskSupport":"required"},"outputSchema":{"type":"object"}}
                """);
        var schema = json.createObjectNode().put("type", "object");
        var tool = new McpTool("remote.name", "description", schema, metadata);
        metadata.removeAll();
        tool.metadata().removeAll();
        schema.removeAll();
        tool.inputSchema().removeAll();
        assertTrue(tool.requiresTask());
        assertEquals("object", tool.inputSchema().path("type").asText());
        assertTrue(tool.metadata().has("outputSchema"));
    }
}
