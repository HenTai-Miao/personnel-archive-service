/* 对外错误 DTO：稳定暴露机器错误码与安全消息，不泄露异常栈和内部对象。 */
package com.company.personnelarchive.common;

import java.util.Map;

public record ApiError(String code, String message, String timestamp,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                       Map<String, String> details) {
    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.name(), message, java.time.Instant.now().toString(), null);
    }

    public static ApiError withRequestId(ErrorCode code, String message, String requestId) {
        return new ApiError(code.name(), message, java.time.Instant.now().toString(),
            Map.of("requestId", requestId));
    }
}
