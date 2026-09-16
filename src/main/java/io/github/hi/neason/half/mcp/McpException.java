package io.github.hi.neason.half.mcp;

import java.io.IOException;
import java.util.OptionalInt;

/** MCP 协议错误；只保留本地安全描述及可选的 JSON-RPC 错误码。 */
public final class McpException extends IOException {
    private static final long serialVersionUID = 1L;
    private final Integer code;

    public McpException(String safeMessage) {
        super(safeMessage);
        code = null;
    }

    public McpException(String safeMessage, int code) {
        super(safeMessage);
        this.code = code;
    }

    public OptionalInt code() { return code == null ? OptionalInt.empty() : OptionalInt.of(code); }
}
