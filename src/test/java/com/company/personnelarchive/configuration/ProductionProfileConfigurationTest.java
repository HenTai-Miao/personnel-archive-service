/* 生产配置回归测试：prod 使用明确的五位端口，同时保留环境覆盖和本地默认值。 */
package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionProfileConfigurationTest {
    @Test
    void usesFiveDigitPortInEveryEnvironment() throws IOException {
        assertThat(resolvedServerPort(null, "src/main/resources/application.yml"))
            .isEqualTo("18080");
        assertThat(resolvedServerPort(null, "config/application-local.yml"))
            .isEqualTo("18080");
        assertThat(resolvedServerPort(null,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("18080");
    }

    @Test
    void allowsOperationsToOverrideProductionPort() throws IOException {
        assertThat(resolvedServerPort("28080",
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("28080");
    }

    @Test
    void usesKingbaseForProductionAndKeepsMysqlForLocal() throws IOException {
        assertThat(resolvedProperty("spring.datasource.url", Map.of(),
            "src/main/resources/application.yml",
            "config/application-local.yml"))
            .startsWith("jdbc:mysql://127.0.0.1:3306/personnel_archive");
        assertThat(resolvedProperty("spring.datasource.url", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("jdbc:kingbase8://10.141.29.96:54321/personnel_archive");
        assertThat(resolvedProperty("spring.datasource.driver-class-name", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("com.kingbase8.Driver");
        assertThat(resolvedProperty("spring.flyway.url", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("jdbc:postgresql://10.141.29.96:54321/personnel_archive");
        assertThat(resolvedProperty("spring.flyway.locations", Map.of(),
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("classpath:db/kingbase");
    }

    @Test
    void allowsOperationsToOverrideProductionDatabaseSettings() throws IOException {
        Map<String, String> overrides = Map.of(
            "PA_DB_URL", "jdbc:kingbase8://db.example:54321/archive",
            "PA_FLYWAY_DB_URL", "jdbc:postgresql://db.example:54321/archive",
            "PA_DB_USERNAME", "archive_runtime",
            "PA_DB_PASSWORD", "injected-secret");

        assertThat(resolvedProperty("spring.datasource.url", overrides,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("jdbc:kingbase8://db.example:54321/archive");
        assertThat(resolvedProperty("spring.flyway.url", overrides,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("jdbc:postgresql://db.example:54321/archive");
        assertThat(resolvedProperty("spring.datasource.username", overrides,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("archive_runtime");
        assertThat(resolvedProperty("spring.datasource.password", overrides,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("injected-secret");
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
        return environment.getRequiredProperty(property);
    }
}
