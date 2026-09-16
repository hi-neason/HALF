package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** 传输只负责完整消息收发；请求关联、进度和取消语义由 McpConnection 管理。 */
interface McpTransport extends AutoCloseable {
    void start(Consumer<JsonNode> receive, BiConsumer<String, McpException> failure) throws IOException, InterruptedException;

    /** 非阻塞启动发送；future 表示写入或 HTTP 接受，响应通过 receive 独立交付。 */
    CompletableFuture<Void> send(ObjectNode message);

    /** 释放本请求的读取资源；不等价于向 Server 发送取消通知。 */
    default void cancelRequest(String id) { }

    default void protocolVersion(String version) { }

    /** notifications/initialized 发送成功后调用，可启动可选的服务端通知通道。 */
    default void initialized() { }

    @Override void close();
}
