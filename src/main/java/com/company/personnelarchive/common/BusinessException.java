/* 可预期业务异常：携带稳定 ErrorCode，供统一异常边界转换为 HTTP 响应。 */
package com.company.personnelarchive.common;

public class BusinessException extends RuntimeException {
    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        this(code, code.message());
    }

    public BusinessException(ErrorCode code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
