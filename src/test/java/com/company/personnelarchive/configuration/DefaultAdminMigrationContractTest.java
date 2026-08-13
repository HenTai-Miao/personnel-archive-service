package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAdminMigrationContractTest {
    static final String DEFAULT_PASSWORD_HASH =
        "$2a$12$ZeUKR0IcwyMU1Wye4d6T9Oc/NG3ZRF3a69JSHnnXiPrHuLqxI/nBi";
    private static final Path MYSQL = Path.of(
        "src/main/resources/db/migration/V8__seed_default_admin.sql");

    @Test
    void seedsTheBcryptAdminInMysql() throws IOException {
        String mysql = Files.readString(MYSQL);

        assertThat(mysql)
            .contains("'hr_6298bc833379'")
            .contains("'人资部门'")
            .contains("tenant_code = 'DEFAULT'")
            .contains("role_code = 'ADMIN'")
            .contains("'" + DEFAULT_PASSWORD_HASH + "'")
            .contains("failed_login_count = 0")
            .contains("locked_until = NULL")
            .contains("password_changed_at = CURRENT_TIMESTAMP(6)")
            .contains("updated_at = CURRENT_TIMESTAMP(6)");
        assertThat(DEFAULT_PASSWORD_HASH)
            .startsWith("$2a$12$")
            .hasSize(60);
    }

    @Test
    void usesMysqlIdempotentUpserts() throws IOException {
        assertThat(Files.readString(MYSQL))
            .contains("ON DUPLICATE KEY UPDATE")
            .contains("role_id = VALUES(role_id)");
    }
}
