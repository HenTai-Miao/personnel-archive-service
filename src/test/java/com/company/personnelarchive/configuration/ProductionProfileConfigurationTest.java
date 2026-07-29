/* 生产配置回归测试：prod 使用明确的五位端口，同时保留环境覆盖和本地默认值。 */
package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionProfileConfigurationTest {
    @Test
    void keepsLocalDefaultAndUsesFiveDigitProductionPort() throws IOException {
        assertThat(resolvedServerPort(null, "src/main/resources/application.yml"))
            .isEqualTo("8080");
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

    private static String resolvedServerPort(String override, String... configPaths)
        throws IOException {
        MockEnvironment environment = new MockEnvironment();
        if (override != null) {
            environment.setProperty("PA_SERVER_PORT", override);
        }
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
        return environment.getRequiredProperty("server.port");
    }
}
