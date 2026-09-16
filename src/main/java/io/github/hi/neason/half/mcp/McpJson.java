package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 严格 JSON 与 UTF-8；不在异常中保留远端正文。 */
final class McpJson {
    static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private McpJson() { }

    static ObjectNode object() { return JSON.createObjectNode(); }

    static byte[] encode(ObjectNode message) throws McpException {
        try {
            byte[] bytes = JSON.writeValueAsBytes(message);
            if (bytes.length > MAX_MESSAGE_BYTES) throw new McpException("MCP message exceeds size limit");
            return bytes;
        } catch (JsonProcessingException error) {
            throw new McpException("Unable to encode MCP message");
        }
    }

    static JsonNode parse(byte[] bytes) throws McpException {
        if (bytes.length > MAX_MESSAGE_BYTES) throw new McpException("MCP message exceeds size limit");
        try {
            return parse(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException error) {
            throw new McpException("Invalid MCP UTF-8 message");
        }
    }

    static JsonNode parse(String text) throws McpException {
        if (text.length() > MAX_MESSAGE_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new McpException("MCP message exceeds size limit");
        }
        try {
            var value = JSON.readTree(text);
            if (value == null || !value.isObject()) throw new McpException("Invalid MCP JSON-RPC message");
            return value;
        } catch (JsonProcessingException error) {
            throw new McpException("Invalid MCP JSON message");
        }
    }
}
