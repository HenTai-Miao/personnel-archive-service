package com.company.personnelarchive.configuration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class DefaultAdminCredentialRotationMigrationIntegrationTest {
    private static final String LEGACY_USERNAME = "hr_6298bc833379";
    private static final String TARGET_USERNAME = "hr_dept";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("personnel_archive")
        .withUsername("test_user")
        .withPassword("test_password");

    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabaseAtV14() {
        Flyway latest = flyway(null);
        latest.clean();
        flyway("14").migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @Test
    void rotatesTheLegacyAdministratorInPlaceAndRevokesOnlyActiveSessions() {
        long originalId = defaultAdminId(LEGACY_USERNAME);
        jdbc.update("""
            UPDATE sys_user
            SET password_hash='outdated-test-hash',is_enabled=0,failed_login_count=4,
                locked_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)
            WHERE id=?
            """, originalId);
        addViewerRole(originalId);
        addSession(originalId, "active-session", false);
        addSession(originalId, "already-revoked-session", true);

        Flyway latest = flyway(null);
        latest.migrate();

        assertFinalAdministrator(originalId);
        assertThat(latest.info().current().getVersion().toString()).isEqualTo("15");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user
            WHERE tenant_id=1 AND normalized_username=?
            """, Integer.class, LEGACY_USERNAME)).isZero();
        assertThat(jdbc.queryForMap("""
            SELECT revoked_at,revoke_reason,revoked_by FROM sys_user_session
            WHERE refresh_token_hash=UNHEX(SHA2('active-session',256))
            """))
            .satisfies(session -> assertThat(session.get("revoked_at")).isNotNull())
            .containsEntry("revoke_reason", "DEFAULT_ADMIN_CREDENTIAL_ROTATION")
            .containsEntry("revoked_by", originalId);
        assertThat(jdbc.queryForMap("""
            SELECT revoked_at,revoke_reason,revoked_by FROM sys_user_session
            WHERE refresh_token_hash=UNHEX(SHA2('already-revoked-session',256))
            """))
            .containsEntry("revoked_at", LocalDateTime.of(2026, 8, 14, 8, 0))
            .containsEntry("revoke_reason", "PREVIOUS_TEST_REVOCATION")
            .containsEntry("revoked_by", originalId);
    }

    @Test
    void normalizesAnAlreadyRenamedAdministrator() {
        long originalId = defaultAdminId(LEGACY_USERNAME);
        jdbc.update("""
            UPDATE sys_user
            SET username=?,normalized_username=?,password_hash='manual-test-hash',
                display_name='测试临时名称',is_enabled=0,failed_login_count=3,
                locked_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)
            WHERE id=?
            """, TARGET_USERNAME, TARGET_USERNAME, originalId);
        addViewerRole(originalId);
        addSession(originalId, "manual-rename-session", false);

        flyway(null).migrate();

        assertFinalAdministrator(originalId);
        assertThat(jdbc.queryForMap("""
            SELECT revoked_at,revoke_reason FROM sys_user_session
            WHERE refresh_token_hash=UNHEX(SHA2('manual-rename-session',256))
            """))
            .satisfies(session -> assertThat(session.get("revoked_at")).isNotNull())
            .containsEntry("revoke_reason", "DEFAULT_ADMIN_CREDENTIAL_ROTATION");
    }

    @Test
    void rejectsASecondTargetAccountWithoutMergingUsers() {
        long originalId = defaultAdminId(LEGACY_USERNAME);
        jdbc.update("""
            INSERT INTO sys_user
              (tenant_id,username,normalized_username,password_hash,display_name)
            VALUES (1,?,?,'conflict-test-hash','冲突测试账号')
            """, TARGET_USERNAME, TARGET_USERNAME);
        long conflictingId = defaultAdminId(TARGET_USERNAME);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(FlywayException.class);

        assertThat(jdbc.queryForMap("""
            SELECT normalized_username,password_hash FROM sys_user WHERE id=?
            """, originalId))
            .containsEntry("normalized_username", LEGACY_USERNAME)
            .containsEntry("password_hash", DefaultAdminMigrationContractTest.INITIAL_PASSWORD_HASH);
        assertThat(jdbc.queryForMap("""
            SELECT normalized_username,password_hash FROM sys_user WHERE id=?
            """, conflictingId))
            .containsEntry("normalized_username", TARGET_USERNAME)
            .containsEntry("password_hash", "conflict-test-hash");
    }

    @Test
    void rejectsAStandardDatabaseWhoseDefaultAdministratorIsMissing() {
        long originalId = defaultAdminId(LEGACY_USERNAME);
        jdbc.update("DELETE FROM sys_user_role WHERE tenant_id=1 AND user_id=?", originalId);
        jdbc.update("DELETE FROM sys_user WHERE tenant_id=1 AND id=?", originalId);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(FlywayException.class);

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user
            WHERE tenant_id=1 AND normalized_username IN (?,?)
            """, Integer.class, LEGACY_USERNAME, TARGET_USERNAME)).isZero();
    }

    private void assertFinalAdministrator(long expectedId) {
        assertThat(jdbc.queryForMap("""
            SELECT id,username,normalized_username,password_hash,display_name,
                   is_enabled + 0 AS enabled_flag,failed_login_count,locked_until
            FROM sys_user WHERE tenant_id=1 AND normalized_username=?
            """, TARGET_USERNAME))
            .containsEntry("id", expectedId)
            .containsEntry("username", TARGET_USERNAME)
            .containsEntry("normalized_username", TARGET_USERNAME)
            .containsEntry("password_hash", DefaultAdminMigrationContractTest.ROTATED_PASSWORD_HASH)
            .containsEntry("display_name", "人资部门")
            .containsEntry("enabled_flag", 1L)
            .containsEntry("failed_login_count", 0)
            .containsEntry("locked_until", null);
        assertThat(jdbc.queryForList("""
            SELECT role.role_code
            FROM sys_user_role user_role
            JOIN sys_role role
              ON role.id=user_role.role_id AND role.tenant_id=user_role.tenant_id
            WHERE user_role.tenant_id=1 AND user_role.user_id=?
            ORDER BY role.role_code
            """, String.class, expectedId)).containsExactly("ADMIN");
    }

    private long defaultAdminId(String normalizedUsername) {
        return jdbc.queryForObject("""
            SELECT id FROM sys_user WHERE tenant_id=1 AND normalized_username=?
            """, Long.class, normalizedUsername);
    }

    private void addViewerRole(long userId) {
        jdbc.update("""
            INSERT INTO sys_user_role (tenant_id,user_id,role_id)
            SELECT 1,?,id FROM sys_role WHERE tenant_id=1 AND role_code='VIEWER'
            """, userId);
    }

    private void addSession(long userId, String tokenSeed, boolean alreadyRevoked) {
        if (alreadyRevoked) {
            jdbc.update("""
                INSERT INTO sys_user_session
                  (tenant_id,user_id,refresh_token_hash,issued_at,access_expires_at,
                   refresh_expires_at,revoked_at,revoked_by,revoke_reason)
                VALUES
                  (1,?,UNHEX(SHA2(?,256)),CURRENT_TIMESTAMP(6),
                   DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),
                   DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),
                   '2026-08-14 08:00:00.000000',?,'PREVIOUS_TEST_REVOCATION')
                """, userId, tokenSeed, userId);
            return;
        }
        jdbc.update("""
            INSERT INTO sys_user_session
              (tenant_id,user_id,refresh_token_hash,issued_at,access_expires_at,
               refresh_expires_at)
            VALUES
              (1,?,UNHEX(SHA2(?,256)),CURRENT_TIMESTAMP(6),
               DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),
               DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY))
            """, userId, tokenSeed);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
