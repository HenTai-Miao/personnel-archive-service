/* Spring 上下文生命周期诊断：启动失败时不输出可能包含业务值的异常消息。 */
package com.company.personnelarchive.configuration;

import com.company.personnelarchive.common.OperationalLog;
import com.company.personnelarchive.common.SafeExceptionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class ServiceLifecycleLogger implements ApplicationListener<SpringApplicationEvent> {
    private static final Logger log = LoggerFactory.getLogger(ServiceLifecycleLogger.class);
    private final long started = OperationalLog.started();

    @Override
    public void onApplicationEvent(SpringApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
            Environment environment = prepared.getEnvironment();
            log.info("event=service_starting version={} javaVersion={} profile={} port={} logFile={} durationMs={}",
                safeToken(version()), safeToken(System.getProperty("java.version")), profiles(environment),
                port(environment), logFile(environment), OperationalLog.elapsedMillis(started));
        } else if (event instanceof ApplicationFailedEvent failed) {
            log.error("event=service_start_failed phase=application_context result=FAILED durationMs={} {}",
                OperationalLog.elapsedMillis(started), SafeExceptionSummary.describe(failed.getException()));
        }
    }

    private static String profiles(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) profiles = environment.getDefaultProfiles();
        return Arrays.stream(profiles).map(ServiceLifecycleLogger::safeToken)
            .collect(Collectors.joining(","));
    }

    private static int port(Environment environment) {
        try {
            int value = Integer.parseInt(environment.getProperty("server.port", "0"));
            return value >= 1 && value <= 65_535 ? value : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String logFile(Environment environment) {
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

    private static String version() {
        String value = ServiceLifecycleLogger.class.getPackage().getImplementationVersion();
        return value == null ? "development" : value;
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String safe = value.replaceAll("[^A-Za-z0-9_.+-]", "_");
        return safe.substring(0, Math.min(safe.length(), 128));
    }
}
