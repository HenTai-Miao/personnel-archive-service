package com.company.personnelarchive.configuration;

import com.company.personnelarchive.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ServiceLifecycleLoggerTest {
    @Test
    void logsStartingMetadataAndSafeFailure() {
        var environment = new MockEnvironment()
            .withProperty("server.port", "18080")
            .withProperty("logging.file.name", "/data/personnel-archive-service/log/log.%d{yyyy-MM-dd}.log");
        environment.setActiveProfiles("prod");
        SpringApplication application = new SpringApplication(Object.class);
        var listener = new ServiceLifecycleLogger();

        try (var logs = LogCapture.attach(ServiceLifecycleLogger.class)) {
            listener.onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                mock(ConfigurableBootstrapContext.class), application, new String[0], environment));
            listener.onApplicationEvent(new ApplicationFailedEvent(application, new String[0], null,
                new IllegalStateException("startup-message-secret-sentinel")));

            assertThat(logs.joined())
                .contains("event=service_starting", "profile=prod", "port=18080",
                    "logFile=/data/personnel-archive-service/log/log.%d{yyyy-MM-dd}.log",
                    "event=service_start_failed", "phase=application_context",
                    "exceptionType=java.lang.IllegalStateException", "durationMs=")
                .doesNotContain("startup-message-secret-sentinel");
        }
    }
}
