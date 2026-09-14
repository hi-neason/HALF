package io.github.hi.neason.half.model;

import java.util.Objects;

/** 一条纯文本消息；历史由调用方按时间顺序传入。 */
public record ChatMessage(Role role, String text) {
    public enum Role { SYSTEM, USER, ASSISTANT }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text);
    }
}
