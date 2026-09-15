package io.github.hi.neason.half.model.http;

/** SSE 字段解析结果；不解释 data 内的供应商协议。 */
public record SseFrame(String event, String data) { }
