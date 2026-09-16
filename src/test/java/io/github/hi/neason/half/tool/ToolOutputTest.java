package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolOutputTest {
    @Test
    void explicitFailurePreservesTextDetailsAndTruncation() throws Exception {
        var details = JsonNodeFactory.instance.objectNode().put("reason", "not_found");
        var output = new ToolOutput("x".repeat(100), details, true);
        details.put("reason", "changed");
        ContextualTool tool = new ContextualTool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("lookup", "Lookup", "{\"type\":\"object\"}"); }
            @Override public ToolOutput execute(ObjectNode arguments, ToolContext context) { return output; }
        };
        var result = new ToolExecutor(new ToolRegistry(List.of(tool)), 64)
                .execute(new ContentBlock.ToolCall("one", "lookup", "{}"));
        assertEquals(ToolResult.Status.EXECUTION_FAILED, result.status());
        assertTrue(result.truncated());
        assertEquals(64, result.output().length());
        assertEquals("not_found", result.details().path("reason").asText());
        assertTrue(result.toMessage().text().contains("EXECUTION_FAILED"));
    }

    @Test
    void existingConstructorsRemainSuccessfulAndDefensivelyCopyDetails() {
        var details = JsonNodeFactory.instance.objectNode().put("value", 1);
        var output = new ToolOutput("ok", details);
        assertFalse(output.isError());
        assertFalse(new ToolOutput("ok").isError());
        ((ObjectNode) output.details()).put("value", 2);
        assertEquals(1, output.details().path("value").asInt());
    }
}
