/* Flyway 启动诊断：显式记录安全的数据库目标、版本、结果和耗时。 */
package com.company.personnelarchive.configuration;

import com.company.personnelarchive.common.OperationalLog;
import com.company.personnelarchive.common.SafeExceptionSummary;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class OperationalFlywayMigrationStrategy implements FlywayMigrationStrategy {
    private static final Logger log = LoggerFactory.getLogger(OperationalFlywayMigrationStrategy.class);
    private final Environment environment;

    public OperationalFlywayMigrationStrategy(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void migrate(Flyway flyway) {
        SafeJdbcTarget database = SafeJdbcTarget.parse(environment.getProperty("spring.datasource.url"));
        long connectionStarted = OperationalLog.started();
        log.info("event=database_connection_started dbHost={} dbPort={} dbSchema={}",
            database.host(), database.port(), database.schema());

        String currentVersion;
        try {
            currentVersion = version(flyway.info().current());
            log.info("event=database_connection_completed dbHost={} dbPort={} dbSchema={} "
                    + "currentVersion={} result=SUCCESS durationMs={}",
                database.host(), database.port(), database.schema(), currentVersion,
                OperationalLog.elapsedMillis(connectionStarted));
        } catch (RuntimeException exception) {
            long duration = OperationalLog.elapsedMillis(connectionStarted);
            log.error("event=database_connection_failed dbHost={} dbPort={} dbSchema={} result=FAILED "
                    + "durationMs={} {}",
                database.host(), database.port(), database.schema(), duration,
                SafeExceptionSummary.describe(exception));
            log.error("event=flyway_migration_failed phase=database_connection currentVersion=unknown "
                    + "result=FAILED durationMs={} {}",
                duration, SafeExceptionSummary.describe(exception));
            throw exception;
        }

        long migrationStarted = OperationalLog.started();
        log.info("event=flyway_migration_started currentVersion={} targetVersion=latest", currentVersion);
        try {
            MigrateResult result = flyway.migrate();
            String migrationResult = result != null && result.success ? "SUCCESS" : "FAILED";
            log.info("event=flyway_migration_completed initialVersion={} finalVersion={} migrationCount={} "
                    + "result={} durationMs={}",
                value(result == null ? null : result.initialSchemaVersion),
                value(result == null ? null : result.targetSchemaVersion),
                result == null ? 0 : Math.max(0, result.migrationsExecuted), migrationResult,
                OperationalLog.elapsedMillis(migrationStarted));
        } catch (RuntimeException exception) {
            log.error("event=flyway_migration_failed phase=migrate currentVersion={} result=FAILED durationMs={} {}",
                currentVersion, OperationalLog.elapsedMillis(migrationStarted),
                SafeExceptionSummary.describe(exception));
            throw exception;
        }
    }

    private static String version(MigrationInfo migration) {
        return migration == null || migration.getVersion() == null ? "none" : value(migration.getVersion().toString());
    }

    private static String value(String value) {
        if (value == null || value.isBlank()) return "none";
        String safe = value.replaceAll("[^A-Za-z0-9_.+-]", "_");
        return safe.substring(0, Math.min(safe.length(), 64));
    }
}
