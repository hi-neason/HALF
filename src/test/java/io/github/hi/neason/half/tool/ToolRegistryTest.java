package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test
    void snapshotsRegistrationOrderAndExposesImmutableDefinitions() {
        Tool first = tool("first");
        Tool second = tool("second");
        var tools = new ArrayList<>(List.of(first, second));
        var registry = new ToolRegistry(tools);
        var definitions = registry.definitions();
        tools.clear();

        assertEquals(List.of(first.definition(), second.definition()), definitions);
        assertSame(first, registry.find("first").orElseThrow());
        assertSame(second, registry.find("second").orElseThrow());
        assertTrue(registry.find("missing").isEmpty());
        assertThrows(UnsupportedOperationException.class, definitions::clear);
        assertEquals(definitions, registry.definitions());
    }

    @Test
    void rejectsDifferentImplementationsWithTheSameName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRegistry(List.of(tool("same"), tool("same"))));
    }

    @Test
    void supportsAnEmptyRegistry() {
        var registry = new ToolRegistry(List.of());
        assertTrue(registry.definitions().isEmpty());
        assertTrue(registry.find("missing").isEmpty());
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Test tool", "{\"type\":\"object\"}");
            }

            @Override
            public String execute(ObjectNode arguments) {
                throw new AssertionError("Registry must not execute tools");
            }
        };
    }
}
