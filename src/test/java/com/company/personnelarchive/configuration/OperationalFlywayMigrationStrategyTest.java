package com.company.personnelarchive.configuration;

import com.company.personnelarchive.support.LogCapture;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalFlywayMigrationStrategyTest {
    private static final String URL =
        "jdbc:mysql://127.0.0.1:3306/personnel_archive?user=user-secret-sentinel&password=db-secret-sentinel";

    @Test
    void logsConnectionAndMigrationSuccessWithoutCredentials() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        MigrationInfo current = mock(MigrationInfo.class);
        when(flyway.info()).thenReturn(info);
        when(info.current()).thenReturn(current);
        when(current.getVersion()).thenReturn(MigrationVersion.fromVersion("13"));
        MigrateResult result = new MigrateResult();
        result.initialSchemaVersion = "13";
        result.targetSchemaVersion = "14";
        result.migrationsExecuted = 1;
        result.success = true;
        when(flyway.migrate()).thenReturn(result);

        var strategy = new OperationalFlywayMigrationStrategy(environment());
        try (var logs = LogCapture.attach(OperationalFlywayMigrationStrategy.class)) {
            strategy.migrate(flyway);

            assertThat(logs.joined())
                .contains("event=database_connection_started", "dbHost=127.0.0.1", "dbPort=3306",
                    "dbSchema=personnel_archive", "event=database_connection_completed",
                    "currentVersion=13", "result=SUCCESS", "durationMs=",
                    "event=flyway_migration_started", "event=flyway_migration_completed",
                    "initialVersion=13", "finalVersion=14", "migrationCount=1")
                .doesNotContain("jdbc:mysql", "user-secret-sentinel", "db-secret-sentinel");
        }
        verify(flyway).migrate();
    }

    @Test
    void logsConnectionFailureWithoutExceptionMessage() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenThrow(new IllegalStateException("connection-message-secret-sentinel"));
        var strategy = new OperationalFlywayMigrationStrategy(environment());

        try (var logs = LogCapture.attach(OperationalFlywayMigrationStrategy.class)) {
            assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalStateException.class);

            assertThat(logs.joined())
                .contains("event=database_connection_started", "event=database_connection_failed",
                    "event=flyway_migration_failed", "phase=database_connection",
                    "exceptionType=java.lang.IllegalStateException", "durationMs=")
                .doesNotContain("connection-message-secret-sentinel", "jdbc:mysql",
                    "user-secret-sentinel", "db-secret-sentinel");
        }
    }

    @Test
    void logsMigrationFailureWithoutExceptionMessage() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.current()).thenReturn(null);
        when(flyway.migrate()).thenThrow(new IllegalArgumentException("migration-message-secret-sentinel"));
        var strategy = new OperationalFlywayMigrationStrategy(environment());

        try (var logs = LogCapture.attach(OperationalFlywayMigrationStrategy.class)) {
            assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalArgumentException.class);

            assertThat(logs.joined())
                .contains("event=database_connection_completed", "currentVersion=none",
                    "event=flyway_migration_started", "event=flyway_migration_failed",
                    "phase=migrate", "exceptionType=java.lang.IllegalArgumentException", "durationMs=")
                .doesNotContain("migration-message-secret-sentinel", "jdbc:mysql",
                    "user-secret-sentinel", "db-secret-sentinel");
        }
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("spring.datasource.url", URL);
    }
}
