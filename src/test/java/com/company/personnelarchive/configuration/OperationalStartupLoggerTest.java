package com.company.personnelarchive.configuration;

import com.company.personnelarchive.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalStartupLoggerTest {
    @Test
    void extractsOnlyTheSafeMysqlTarget() {
        SafeJdbcTarget target = SafeJdbcTarget.parse(
            "jdbc:mysql://127.0.0.1:3306/personnel_archive?user=user-secret-sentinel&password=db-secret-sentinel");

        assertThat(target).isEqualTo(new SafeJdbcTarget("127.0.0.1", 3306, "personnel_archive"));
        assertThat(SafeJdbcTarget.parse("jdbc:mysql://bad\nhost:3306/schema"))
            .isEqualTo(SafeJdbcTarget.unknown());
        assertThat(SafeJdbcTarget.parse("jdbc:mysql://127.0.0.1:70000/schema"))
            .isEqualTo(SafeJdbcTarget.unknown());
    }

    @Test
    void logsReadyMetadataWithoutUrlsOrSecurityValues() {
        var environment = new MockEnvironment()
            .withProperty("spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3306/personnel_archive?user=user-secret-sentinel&password=db-secret-sentinel")
            .withProperty("server.port", "18080")
            .withProperty("logging.file.name", "/opt/personnel-archive-service/log/personnel-archive-service.log");
        environment.setActiveProfiles("prod");
        ArchiveProperties properties = new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 100,
                "token-pepper-secret-sentinel", "hmac-key-secret-sentinel",
                "encryption-key-secret-sentinel"), null, null);
        var logger = new OperationalStartupLogger(environment, properties);

        try (var logs = LogCapture.attach(OperationalStartupLogger.class)) {
            logger.logReady(Duration.ofMillis(123));

            assertThat(logs.joined())
                .contains("event=service_ready", "profile=prod", "port=18080",
                    "dbHost=127.0.0.1", "dbPort=3306", "dbSchema=personnel_archive",
                    "securityKeysConfigured=true", "durationMs=123")
                .doesNotContain("jdbc:mysql", "user-secret-sentinel", "db-secret-sentinel",
                    "token-pepper-secret-sentinel", "hmac-key-secret-sentinel",
                    "encryption-key-secret-sentinel");
        }
    }
}
