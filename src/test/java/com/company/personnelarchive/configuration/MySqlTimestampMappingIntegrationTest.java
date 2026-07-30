/* JDBC 时区回归测试：生产连接参数必须把中国本地 DATETIME 映射为正确时间点。 */
package com.company.personnelarchive.configuration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MySqlTimestampMappingIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("personnel_archive")
        .withUsername("test_user")
        .withPassword("test_password");

    @ParameterizedTest(name = "{0} maps China wall-clock DATETIME to the correct instant")
    @ValueSource(strings = "src/main/resources/application-dev.yml")
    void mapsChinaWallClockDatetimeToCorrectInstant(String configPath) throws IOException {
        // Given
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            testJdbcUrl(configPath), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS pa_timestamp_probe");
        jdbc.execute("CREATE TABLE pa_timestamp_probe (captured_at DATETIME(6) NOT NULL)");
        jdbc.update("INSERT INTO pa_timestamp_probe (captured_at) VALUES ('2026-07-24 11:06:01.887644')");

        // When
        Instant actual = jdbc.queryForObject("SELECT captured_at FROM pa_timestamp_probe",
            (resultSet, rowNumber) -> resultSet.getTimestamp("captured_at").toInstant());

        // Then
        assertThat(actual).isEqualTo(Instant.parse("2026-07-24T03:06:01.887644Z"));
    }

    private static String testJdbcUrl(String configPath) throws IOException {
        String configuredUrl = configuredDatasourceUrl(configPath);
        int queryStart = configuredUrl.indexOf('?');
        if (queryStart < 0) {
            throw new IllegalStateException("Configured JDBC URL has no query parameters: " + configPath);
        }
        String separator = MYSQL.getJdbcUrl().contains("?") ? "&" : "?";
        return MYSQL.getJdbcUrl() + separator + configuredUrl.substring(queryStart + 1);
    }

    private static String configuredDatasourceUrl(String configPath) throws IOException {
        Object rawValue = new YamlPropertySourceLoader()
            .load("timezone-regression", new FileSystemResource(configPath))
            .stream()
            .map(source -> source.getProperty("spring.datasource.url"))
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing spring.datasource.url: " + configPath));
        return new MockEnvironment().resolveRequiredPlaceholders(rawValue.toString());
    }
}
