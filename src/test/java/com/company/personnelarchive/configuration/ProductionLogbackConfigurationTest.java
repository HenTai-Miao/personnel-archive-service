/* 生产 Logback 合约：只按日期轮转，不按大小分片，也不自动删除历史日志。 */
package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionLogbackConfigurationTest {
    @Test
    void writesOneUnlimitedReadableLogFileDirectlyPerDay() throws IOException {
        Path configuration = Path.of("src/main/resources/logback-spring.xml");
        assertThat(configuration).isRegularFile();
        String xml = Files.readString(configuration);

        assertThat(xml).contains(
            "ch.qos.logback.core.rolling.TimeBasedRollingPolicy",
            "./log/log.%d{yyyy-MM-dd}.log",
            "DAILY_FILE",
            "CONSOLE");
        assertThat(xml).doesNotContain(
            "<file>",
            ".gz",
            "personnel-archive-service.%d{yyyy-MM-dd}",
            "SizeAndTimeBasedRollingPolicy",
            "maxFileSize",
            "maxHistory",
            "totalSizeCap",
            "%i");
    }

    @Test
    void writesFilesOnlyForProd() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"));

        assertThat(xml).contains("<springProfile name=\"prod\">", "<springProfile name=\"!prod\">");
    }
}
