/* 请求日志边界：生成关联号并只记录固定路由模板，不记录 URI 参数或请求正文。 */
package com.company.personnelarchive.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        String outcome = "THROWN";
        request.setAttribute(RequestCorrelation.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(RequestCorrelation.RESPONSE_HEADER, requestId);
        MDC.put(RequestCorrelation.MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
            outcome = "COMPLETED";
        } finally {
            log.info("event=http_request_completed method={} route={} status={} userId={} outcome={} durationMs={}",
                safeMethod(request.getMethod()), matchedRoute(request), response.getStatus(), userId(request), outcome,
                Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            MDC.remove(RequestCorrelation.MDC_KEY);
        }
    }

    private static String safeMethod(String method) {
        return method != null && method.matches("[A-Z]{1,16}") ? method : "OTHER";
    }

    private static String matchedRoute(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (value == null) return "UNMATCHED";
        String route = String.valueOf(value);
        return route.matches("[A-Za-z0-9_{}./*:-]{1,256}") ? route : "UNMATCHED";
    }

    private static String userId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestCorrelation.USER_ID_ATTRIBUTE);
        return value instanceof Number number && number.longValue() > 0
            ? Long.toString(number.longValue()) : "anonymous";
    }
}
