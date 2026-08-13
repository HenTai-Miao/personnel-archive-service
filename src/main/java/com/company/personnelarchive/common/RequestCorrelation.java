/* HTTP 请求关联号：只接受当前服务端生成并放入 MDC 的值。 */
package com.company.personnelarchive.common;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;

public final class RequestCorrelation {
    public static final String MDC_KEY = "requestId";
    public static final String RESPONSE_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestCorrelation.class.getName() + ".requestId";
    public static final String USER_ID_ATTRIBUTE = RequestCorrelation.class.getName() + ".userId";

    private RequestCorrelation() {}

    public static Optional<String> currentId() {
        return Optional.ofNullable(MDC.get(MDC_KEY)).filter(value -> !value.isBlank());
    }

    public static String currentIdOrNew() {
        return currentId().orElseGet(() -> UUID.randomUUID().toString());
    }
}
