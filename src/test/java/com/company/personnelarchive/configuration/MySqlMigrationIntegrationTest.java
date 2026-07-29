/* MySQL 迁移集成测试：在真实数据库验证 Flyway 表、有效档案唯一性和空标识兼容性。 */
package com.company.personnelarchive.configuration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
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
        assertThat(jdbc.queryForObject("""
            SELECT DATA_TYPE
            FROM information_schema.columns
            WHERE table_schema='personnel_archive'
              AND table_name='pa_person'
              AND column_name='delete_reason'
            """, String.class)).isEqualTo("longtext");
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
        long activePersonId = jdbc.queryForObject(
            "SELECT id FROM pa_person WHERE employee_no='T-001' AND is_deleted=0", Long.class);
        String familyInsert = """
            INSERT INTO pa_family_member
              (tenant_id,person_id,relationship_name,member_name,normalized_member_name,sort_order)
            VALUES (1,?,'测试称谓','测试成员','测试成员',?)
            """;
        jdbc.update(familyInsert, activePersonId, 10);
        assertThat(jdbc.queryForObject(
            "SELECT sort_order FROM pa_family_member WHERE person_id=?", Integer.class, activePersonId))
            .isEqualTo(10);
        assertThatThrownBy(() -> jdbc.update(familyInsert, activePersonId, 0))
            .isInstanceOf(DataAccessException.class);

        String optionalInsert = "INSERT INTO pa_person (tenant_id,employee_no,person_name,normalized_name,template_version,created_by,updated_by) VALUES (1,'','','','V1',?,?)";
        jdbc.update(optionalInsert, userId, userId);
        jdbc.update(optionalInsert, userId, userId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pa_person WHERE employee_no='' AND is_deleted=0", Integer.class))
            .isEqualTo(2);

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema='personnel_archive' AND data_type='varchar'
              AND ((table_name='pa_person' AND column_name IN
                    ('birth_date','political_join_date','declaration_date'))
                OR (table_name='pa_employment' AND column_name IN
                    ('work_start_date','dispatched_to_bidding_company_date','title_or_skill_acquired_date'))
                OR (table_name='pa_education' AND column_name IN ('start_date','end_date'))
                OR (table_name='pa_work_experience' AND column_name IN ('start_date','end_date'))
                OR (table_name='pa_reward_punishment' AND column_name='occurred_date')
                OR (table_name='pa_family_member' AND column_name='birth_date'))
            """, Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema='personnel_archive' AND column_name LIKE '%\\_date\\_text'
            """, Integer.class)).isZero();
        jdbc.update("UPDATE pa_person SET birth_date='1988' WHERE id=?", activePersonId);
        jdbc.update("""
            UPDATE pa_family_member
            SET birth_date='1990.02',age_text='36'
            WHERE person_id=?
            """, activePersonId);
        assertThat(jdbc.queryForMap("SELECT birth_date FROM pa_person WHERE id=?", activePersonId))
            .containsEntry("birth_date", "1988");
        assertThat(jdbc.queryForMap(
            "SELECT birth_date,age_text FROM pa_family_member WHERE person_id=?", activePersonId))
            .containsEntry("birth_date", "1990.02")
            .containsEntry("age_text", "36");
    }
}
