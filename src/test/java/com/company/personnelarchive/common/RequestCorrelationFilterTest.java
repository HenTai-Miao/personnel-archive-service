package com.company.personnelarchive.common;

import com.company.personnelarchive.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationFilterTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void createsServerOwnedIdExposesItAndRemovesMdcAfterTheRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/persons/7");
        request.addHeader("X-Request-Id", "client-controlled-id");
        var response = new MockHttpServletResponse();
        var seenInsideChain = new AtomicReference<String>();

        new RequestCorrelationFilter().doFilter(request, response, (servletRequest, servletResponse) -> {
            seenInsideChain.set(MDC.get(RequestCorrelation.MDC_KEY));
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/persons/{personId}");
            response.setStatus(200);
        });

        String responseId = response.getHeader(RequestCorrelation.RESPONSE_HEADER);
        assertThat(responseId).isEqualTo(seenInsideChain.get()).isNotEqualTo("client-controlled-id");
        assertThat(UUID.fromString(responseId)).isNotNull();
        assertThat(request.getAttribute(RequestCorrelation.REQUEST_ID_ATTRIBUTE)).isEqualTo(responseId);
        assertThat(MDC.get(RequestCorrelation.MDC_KEY)).isNull();
    }

    @Test
    void logsOnlyTheMatchedRouteAndNeverTheQueryOrAuthorizationHeader() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/persons/7");
        request.setQueryString("name=query-secret-sentinel");
        request.addHeader("Authorization", "Bearer authorization-secret-sentinel");
        var response = new MockHttpServletResponse();

        try (var logs = LogCapture.attach(RequestCorrelationFilter.class)) {
            new RequestCorrelationFilter().doFilter(request, response, (servletRequest, servletResponse) -> {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/api/v1/persons/{personId}");
                request.setAttribute(RequestCorrelation.USER_ID_ATTRIBUTE, 9L);
                response.setStatus(204);
            });

            assertThat(logs.joined())
                .contains("event=http_request_completed", "method=GET",
                    "route=/api/v1/persons/{personId}", "status=204", "userId=9")
                .doesNotContain("query-secret-sentinel", "authorization-secret-sentinel", "/api/v1/persons/7");
        }
    }

    @Test
    void cleansMdcWhenTheFilterChainThrows() {
        var request = new MockHttpServletRequest("POST", "/api/v1/imports");
        var response = new MockHttpServletResponse();

        try (var logs = LogCapture.attach(RequestCorrelationFilter.class)) {
            assertThatThrownBy(() -> new RequestCorrelationFilter().doFilter(
                request, response, (servletRequest, servletResponse) -> {
                    throw new IllegalStateException("failure-message-must-not-be-logged");
                }))
                .isInstanceOf(IllegalStateException.class);

            assertThat(MDC.get(RequestCorrelation.MDC_KEY)).isNull();
            assertThat(logs.joined())
                .contains("event=http_request_completed", "outcome=THROWN")
                .doesNotContain("failure-message-must-not-be-logged");
        }
    }
}
