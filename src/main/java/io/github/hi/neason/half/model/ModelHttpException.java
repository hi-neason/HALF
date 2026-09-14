package io.github.hi.neason.half.model;

import java.io.IOException;

/** 服务端返回非 2xx 状态；不将响应正文或凭据写入异常信息。 */
public final class ModelHttpException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int statusCode;

    public ModelHttpException(int statusCode) {
        super("Model endpoint returned HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
