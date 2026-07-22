/* MySQL 迁移集成测试：在真实数据库验证 Flyway 表、有效档案唯一性和空标识兼容性。 */
package com.company.personnelarchive.configuration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("personnel_archive")
        .withUsername("test_user")
        .withPassword("test_password");

    @Test
    void migrationEnforcesActiveOnlyUniquenessAndAllowsHistory() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        Integer tableCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='personnel_archive' AND table_name NOT LIKE 'flyway%'", Integer.class);
        assertThat(tableCount).isEqualTo(15);
        jdbc.update("INSERT INTO sys_user (tenant_id,username,normalized_username,password_hash,display_name) VALUES (1,'test_admin','test_admin','not-a-runtime-hash','测试管理员')");
        long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE normalized_username='test_admin'", Long.class);
        byte[] hmac = new byte[32]; Arrays.fill(hmac, (byte) 7);
        String insert = "INSERT INTO pa_person (tenant_id,employee_no,person_name,normalized_name,id_card_ciphertext,id_card_hmac,id_card_last_four,template_version,created_by,updated_by) VALUES (1,?,?,?,?,?,?, 'V1',?,?)";
        jdbc.update(insert, "T-001", "测试甲", "测试甲", new byte[]{1}, hmac, "0012", userId, userId);
        assertThatThrownBy(() -> jdbc.update(insert, "T-001", "测试乙", "测试乙", new byte[]{2}, new byte[32], "0000", userId, userId))
            .hasMessageContaining("Duplicate");
        long firstId = jdbc.queryForObject("SELECT id FROM pa_person WHERE employee_no='T-001' AND is_deleted=0", Long.class);
        jdbc.update("UPDATE pa_person SET is_deleted=1,deleted_at=CURRENT_TIMESTAMP(6),deleted_by=?,delete_reason='TEST',updated_by=? WHERE id=?", userId, userId, firstId);
        jdbc.update(insert, "T-001", "测试乙", "测试乙", new byte[]{2}, new byte[32], "0000", userId, userId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pa_person WHERE employee_no='T-001'", Integer.class)).isEqualTo(2);

        String optionalInsert = "INSERT INTO pa_person (tenant_id,employee_no,person_name,normalized_name,template_version,created_by,updated_by) VALUES (1,'','','','V1',?,?)";
        jdbc.update(optionalInsert, userId, userId);
        jdbc.update(optionalInsert, userId, userId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pa_person WHERE employee_no='' AND is_deleted=0", Integer.class))
            .isEqualTo(2);
    }
}
