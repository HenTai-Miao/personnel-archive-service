/* 服务就绪日志：只输出可运维的非秘密配置元数据。 */
package com.company.personnelarchive.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public final class OperationalStartupLogger {
    private static final Logger log = LoggerFactory.getLogger(OperationalStartupLogger.class);
    private final Environment environment;
    private final ArchiveProperties properties;

    public OperationalStartupLogger(Environment environment, ArchiveProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        logReady(event.getTimeTaken());
    }

    void logReady(Duration duration) {
        SafeJdbcTarget database = SafeJdbcTarget.parse(environment.getProperty("spring.datasource.url"));
        ArchiveProperties.Security security = properties.security();
        log.info("event=security_configuration_checked tokenPepperConfigured={} identityHmacKeyConfigured={} "
                + "identityEncryptionKeyConfigured={}",
            security != null && present(security.tokenPepper()),
            security != null && present(security.identityHmacKey()),
            security != null && present(security.identityEncryptionKey()));
        log.info("event=service_ready version={} javaVersion={} profile={} port={} logFile={} "
                + "dbHost={} dbPort={} dbSchema={} durationMs={}",
            safeToken(version()), safeToken(System.getProperty("java.version")), profiles(), port(), logFile(),
            database.host(), database.port(), database.schema(),
            duration == null ? 0L : Math.max(0L, duration.toMillis()));
    }

    private String profiles() {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) active = environment.getDefaultProfiles();
        return Arrays.stream(active).map(OperationalStartupLogger::safeToken)
            .collect(Collectors.joining(","));
    }

    private int port() {
        try {
            int value = Integer.parseInt(environment.getProperty("server.port", "0"));
            return value >= 1 && value <= 65_535 ? value : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String logFile() {
        String value = environment.getProperty("logging.file.name", "console");
        if ("console".equals(value)) return value;
        if (value.contains("\r") || value.contains("\n") || value.contains("\t") || value.length() > 512) {
            return "unknown";
        }
        try {
            return Path.of(value).toAbsolutePath().normalize().toString();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String version() {
        String value = OperationalStartupLogger.class.getPackage().getImplementationVersion();
        return value == null ? "development" : value;
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String safe = value.replaceAll("[^A-Za-z0-9_.+-]", "_");
        return safe.substring(0, Math.min(safe.length(), 128));
    }
}
