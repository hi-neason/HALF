package io.github.hi.neason.half.tool;

/** 工具主动拒绝参数；不携带待回传给模型的原始输入或内部异常。 */
public final class ToolArgumentsException extends Exception {
    private static final long serialVersionUID = 1L;

    public ToolArgumentsException() {
        super("Arguments do not meet the tool's requirements");
    }
}
