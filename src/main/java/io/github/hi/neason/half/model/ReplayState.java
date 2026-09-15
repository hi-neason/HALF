package io.github.hi.neason.half.model;

import java.util.List;
import java.util.Objects;

/** 不可变协议回放状态；快照是不透明字符串，由对应适配器解析和校验。 */
public record ReplayState(Protocol protocol, List<String> snapshots) {
    public enum Protocol { NONE, OPENAI_RESPONSES }

    public ReplayState {
        Objects.requireNonNull(protocol, "protocol");
        snapshots = List.copyOf(snapshots);
        if (protocol == Protocol.NONE && !snapshots.isEmpty()) {
            throw new IllegalArgumentException("NONE replay state cannot contain snapshots");
        }
        if (snapshots.isEmpty()) protocol = Protocol.NONE;
    }

    public static ReplayState none() { return new ReplayState(Protocol.NONE, List.of()); }

    public static ReplayState responses(List<String> snapshots) {
        return new ReplayState(Protocol.OPENAI_RESPONSES, snapshots);
    }
}
