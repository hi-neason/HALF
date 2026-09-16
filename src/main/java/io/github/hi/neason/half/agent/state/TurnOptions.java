package io.github.hi.neason.half.agent.state;

import java.util.Objects;
import java.util.OptionalInt;

/** 单次用户 turn 的执行选项；一次 turn 可以包含多次模型请求，预算不跨 turn 累计。 */
public record TurnOptions(OptionalInt maxModelCalls) {
    public TurnOptions {
        Objects.requireNonNull(maxModelCalls, "maxModelCalls");
        if (maxModelCalls.isPresent() && maxModelCalls.getAsInt() <= 0) {
            throw new IllegalArgumentException("maxModelCalls must be positive");
        }
    }

    /** 默认每次 turn 最多尝试 8 次模型请求，失败的请求也计数。 */
    public static TurnOptions defaults() { return limited(8); }

    public static TurnOptions limited(int maxModelCalls) {
        return new TurnOptions(OptionalInt.of(maxModelCalls));
    }

    /** 不限制本次 turn 的模型请求次数；正常完成、取消和错误仍会结束执行。 */
    public static TurnOptions unlimited() { return new TurnOptions(OptionalInt.empty()); }

    boolean isModelCallLimitReached(int modelCalls) {
        return maxModelCalls.isPresent() && modelCalls >= maxModelCalls.getAsInt();
    }
}
