/* 环境配置回归测试：公共/dev/prod 各自只承担约定的配置职责。 */
package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionProfileConfigurationTest {
    @Test
    void usesFiveDigitPortInEveryEnvironment() throws IOException {
        assertThat(resolvedServerPort(null, "src/main/resources/application.yml"))
            .isEqualTo("18080");
        assertThat(resolvedServerPort(null,
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml"))
            .isEqualTo("18080");
        assertThat(resolvedServerPort(null,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("18080");
    }

    @Test
    void keepsProductionPortFixed() throws IOException {
        assertThat(resolvedServerPort("28080",
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("18080");
    }

    @Test
    void keepsDatabaseSettingsOutOfCommonConfiguration() throws IOException {
        assertThat(optionalProperty("spring.datasource.url", Map.of(),
            "src/main/resources/application.yml")).isEmpty();
        assertThat(optionalProperty("spring.flyway.locations", Map.of(),
            "src/main/resources/application.yml")).isEmpty();
        assertThat(resolvedProperty("spring.profiles.default", Map.of(),
            "src/main/resources/application.yml")).isEqualTo("dev");
    }

    @Test
    void usesMysqlForDevWithoutAPlaintextBootstrapPassword() throws IOException {
        assertThat(Path.of("src/main/resources/application-dev.yml")).exists();
        assertThat(resolvedProperty("spring.datasource.url", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml"))
            .startsWith("jdbc:mysql://127.0.0.1:3306/personnel_archive");
        assertThat(resolvedProperty("spring.datasource.driver-class-name", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml"))
            .isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(resolvedProperty("spring.flyway.locations", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml"))
            .isEqualTo("classpath:db/migration");
        assertThat(resolvedProperty("personnel-archive.bootstrap-admin.password", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml"))
            .isEmpty();
    }

    @Test
    void usesMysqlForProduction() throws IOException {
        assertThat(resolvedProperty("spring.datasource.url", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai");
        assertThat(resolvedProperty("spring.datasource.username", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("personnel_archive");
        assertThat(resolvedProperty("spring.datasource.password", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml").length())
            .isGreaterThan(0);
        assertThat(resolvedProperty("spring.datasource.driver-class-name", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(resolvedProperty("spring.flyway.locations", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("classpath:db/migration");
        assertThat(optionalProperty("spring.flyway.url", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEmpty();
    }

    @Test
    void embedsStableRequiredSecurityValuesInProduction() throws IOException {
        String[] files = {
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"
        };

        assertThat(resolvedProperty("personnel-archive.security.token-pepper", Map.of(), files))
            .hasSizeGreaterThanOrEqualTo(32);
        assertThat(resolvedProperty("personnel-archive.security.identity-hmac-key", Map.of(), files))
            .hasSizeGreaterThanOrEqualTo(32);
        assertThat(Base64.getDecoder().decode(resolvedProperty(
            "personnel-archive.security.identity-encryption-key", Map.of(), files)))
            .hasSize(32);

        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"));
        assertAbsent(prod, "${PA_TOKEN_PEPPER");
        assertAbsent(prod, "${PA_IDENTITY_HMAC_KEY");
        assertAbsent(prod, "${PA_IDENTITY_ENCRYPTION_KEY");
    }

    @Test
    void writesAndRotatesProductionLogsBesideTheWorkingDirectory() throws IOException {
        String[] files = {
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"
        };

        assertThat(optionalProperty("logging.file.name", Map.of(), files))
            .contains("./log/log.%d{yyyy-MM-dd}.log");
        assertThat(optionalProperty("logging.pattern.correlation", Map.of(), files))
            .hasValueSatisfying(value -> assertThat(value).contains("requestId"));
        assertThat(optionalProperty("logging.logback.rollingpolicy.file-name-pattern", Map.of(), files))
            .isEmpty();
        assertThat(optionalProperty("logging.logback.rollingpolicy.max-file-size", Map.of(), files))
            .isEmpty();
        assertThat(optionalProperty("logging.logback.rollingpolicy.max-history", Map.of(), files))
            .isEmpty();
        assertThat(optionalProperty("logging.logback.rollingpolicy.total-size-cap", Map.of(), files))
            .isEmpty();
    }

    @Test
    void productionConfigurationContainsNoDeploymentPlaceholdersOrLogLimits() throws IOException {
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"));
        assertAbsent(prod, "${PA_DB_");
        assertAbsent(prod, "${PA_SERVER_PORT");
        assertAbsent(prod, "${PA_LOG_");
        assertAbsent(prod, "max-file-size");
        assertAbsent(prod, "max-history");
        assertAbsent(prod, "total-size-cap");
        assertAbsent(prod, "%i");
    }

    @Test
    void doesNotUseAnExternalConfigDirectory() {
        assertThat(Files.exists(Path.of("config"))).isFalse();
    }

    @Test
    void ignoresLegacyProductionDatabaseEnvironmentNames() throws IOException {
        Map<String, String> overrides = Map.of(
            "PA_DB_URL", "jdbc:mysql://db.example:3306/archive?connectionTimeZone=Asia/Shanghai",
            "PA_DB_USERNAME", "archive_runtime",
            "PA_DB_PASSWORD", "injected-secret");

        assertThat(resolvedProperty("spring.datasource.url", overrides,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai");
        assertThat(resolvedProperty("spring.datasource.username", overrides,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("personnel_archive");
        assertThat(resolvedProperty("spring.datasource.password", overrides,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml").equals("injected-secret"))
            .isFalse();
        assertThat(optionalProperty("spring.flyway.url", overrides,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEmpty();
    }

    private static void assertAbsent(String text, String marker) {
        assertThat(text.contains(marker)).as("prod configuration must not contain %s", marker).isFalse();
    }

    private static String resolvedServerPort(String override, String... configPaths)
        throws IOException {
        Map<String, String> overrides = override == null
            ? Map.of()
            : Map.of("PA_SERVER_PORT", override);
        return resolvedProperty("server.port", overrides, configPaths);
    }

    private static String resolvedProperty(
        String property,
        Map<String, String> overrides,
        String... configPaths
    ) throws IOException {
        return environment(overrides, configPaths).getRequiredProperty(property);
    }

    private static Optional<String> optionalProperty(
        String property,
        Map<String, String> overrides,
        String... configPaths
    ) throws IOException {
        return Optional.ofNullable(environment(overrides, configPaths).getProperty(property));
    }

    private static MockEnvironment environment(
        Map<String, String> overrides,
        String... configPaths
    ) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        overrides.forEach(environment::setProperty);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String configPath : configPaths) {
            FileSystemResource resource = new FileSystemResource(configPath);
            if (!resource.exists()) {
                continue;
            }
            List<PropertySource<?>> sources = loader.load(
                configPath,
                resource);
            for (PropertySource<?> source : sources) {
                environment.getPropertySources().addFirst(source);
            }
        }
        return environment;
    }
}
