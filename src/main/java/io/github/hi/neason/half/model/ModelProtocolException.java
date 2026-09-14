package io.github.hi.neason.half.model;

import java.io.IOException;

/** HTTP 成功，但响应格式无效或包含当前模型接口不支持的内容。 */
public final class ModelProtocolException extends IOException {
    private static final long serialVersionUID = 1L;

    public ModelProtocolException(String message) {
        super(message);
    }
}
