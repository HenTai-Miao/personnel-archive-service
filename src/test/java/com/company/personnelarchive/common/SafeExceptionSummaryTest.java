package com.company.personnelarchive.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeExceptionSummaryTest {
    @Test
    void keepsTypesAndCodeLocationsWithoutMessagesOrFileNames() {
        var cause = new IllegalArgumentException(
            "sql-password-sentinel identity-card-sentinel workbook-path-sentinel");
        var exception = new IllegalStateException("top-level-secret-sentinel", cause);
        exception.addSuppressed(new RuntimeException("suppressed-secret-sentinel"));
        exception.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("com.company.personnelarchive.SafeProbe", "execute\nmalicious",
                "workbook-path-sentinel.xlsx", 37)
        });

        String summary = SafeExceptionSummary.describe(exception);

        assertThat(summary)
            .contains("exceptionType=java.lang.IllegalStateException",
                "causeTypes=java.lang.IllegalArgumentException",
                "com.company.personnelarchive.SafeProbe#execute_malicious:37")
            .doesNotContain("top-level-secret-sentinel", "sql-password-sentinel",
                "identity-card-sentinel", "workbook-path-sentinel", "suppressed-secret-sentinel",
                "\n", "\r");
    }
}
