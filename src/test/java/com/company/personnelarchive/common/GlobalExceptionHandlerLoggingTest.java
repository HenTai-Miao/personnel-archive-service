package com.company.personnelarchive.common;

import com.company.personnelarchive.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerLoggingTest {
    private static final String REQUEST_ID = "4eb52f0a-ec4c-427f-aa14-482238308eb5";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void returnsTheSameRequestIdAndLogsNoUnexpectedExceptionMessage() {
        MDC.put(RequestCorrelation.MDC_KEY, REQUEST_ID);
        var handler = new GlobalExceptionHandler();

        try (var logs = LogCapture.attach(GlobalExceptionHandler.class)) {
            var response = handler.handleUnexpected(
                new IllegalStateException("unexpected-password-and-workbook-sentinel"));

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().details()).containsEntry("requestId", REQUEST_ID);
            assertThat(logs.joined())
                .contains("event=request_failed", "exceptionType=java.lang.IllegalStateException")
                .doesNotContain("unexpected-password-and-workbook-sentinel");
        }
    }

    @Test
    void logsOnlyTheBusinessCodeAndNeverItsCustomMessage() {
        MDC.put(RequestCorrelation.MDC_KEY, REQUEST_ID);
        var handler = new GlobalExceptionHandler();

        try (var logs = LogCapture.attach(GlobalExceptionHandler.class)) {
            var response = handler.handleBusiness(
                new BusinessException(ErrorCode.NOT_FOUND, "personal-data-message-sentinel"));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(logs.joined())
                .contains("event=request_business_rejected", "code=NOT_FOUND")
                .doesNotContain("personal-data-message-sentinel");
        }
    }
}
