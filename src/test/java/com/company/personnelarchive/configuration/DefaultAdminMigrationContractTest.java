package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAdminMigrationContractTest {
    static final String INITIAL_PASSWORD_HASH =
        "$2a$12$ZeUKR0IcwyMU1Wye4d6T9Oc/NG3ZRF3a69JSHnnXiPrHuLqxI/nBi";
    static final String ROTATED_PASSWORD_HASH =
        "$2a$12$5VEWfSi2yfCd/o4my8fb.ObFiWLe6b4EzIs5ZYPCXM7oTN6dF8VH.";
    private static final Path V8 = Path.of(
        "src/main/resources/db/migration/V8__seed_default_admin.sql");
    private static final Path V15 = Path.of(
        "src/main/resources/db/migration/V15__rotate_default_admin_credentials.sql");

    @Test
    void keepsThePublishedV8DefaultAdministratorContract() throws IOException {
        String mysql = Files.readString(V8);

        assertThat(mysql)
            .contains("'hr_6298bc833379'")
            .contains("'人资部门'")
            .contains("tenant_code = 'DEFAULT'")
            .contains("role_code = 'ADMIN'")
            .contains("'" + INITIAL_PASSWORD_HASH + "'")
            .contains("failed_login_count = 0")
            .contains("locked_until = NULL")
            .contains("password_changed_at = CURRENT_TIMESTAMP(6)")
            .contains("updated_at = CURRENT_TIMESTAMP(6)")
            .doesNotContain("'hr_dept'");
        assertBcryptCost12(INITIAL_PASSWORD_HASH);
    }

    @Test
    void usesMysqlIdempotentUpsertsInThePublishedV8() throws IOException {
        assertThat(Files.readString(V8))
            .contains("ON DUPLICATE KEY UPDATE")
            .contains("role_id = VALUES(role_id)");
    }

    @Test
    void definesTheV15CredentialRotationSecurityContract() throws IOException {
        String mysql = Files.readString(V15);

        assertThat(mysql)
            .contains("normalized_username IN ('hr_6298bc833379', 'hr_dept')")
            .contains("username = 'hr_dept'")
            .contains("normalized_username = 'hr_dept'")
            .contains("'" + ROTATED_PASSWORD_HASH + "'")
            .contains("display_name = '人资部门'")
            .contains("is_enabled = 1")
            .contains("failed_login_count = 0")
            .contains("locked_until = NULL")
            .contains("UPDATE sys_user_session")
            .contains("revoked_at IS NULL")
            .contains("DEFAULT_ADMIN_CREDENTIAL_ROTATION")
            .contains("role_code = 'ADMIN'")
            .contains("role_code <> 'ADMIN'")
            .doesNotContain("DELETE FROM sys_user\n");
        assertBcryptCost12(ROTATED_PASSWORD_HASH);
    }

    private static void assertBcryptCost12(String hash) {
        assertThat(hash)
            .startsWith("$2a$12$")
            .hasSize(60);
    }
}
