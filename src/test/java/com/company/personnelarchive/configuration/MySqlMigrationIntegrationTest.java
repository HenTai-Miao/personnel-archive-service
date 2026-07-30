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
    void migrationsCanonicalizeActiveValuesAndEnforceFinalConstraints() {
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("9")
            .load()
            .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        assertProjectionColumns(jdbc, "pa_person", "birth_date", "political_join_date", "declaration_date");
        assertProjectionColumns(jdbc, "pa_employment", "work_start_date",
            "dispatched_to_bidding_company_date", "title_or_skill_acquired_date");
        assertProjectionColumns(jdbc, "pa_education", "start_date", "end_date");
        assertProjectionColumns(jdbc, "pa_work_experience", "start_date", "end_date");
        assertProjectionColumns(jdbc, "pa_reward_punishment", "occurred_date");
        assertProjectionColumns(jdbc, "pa_family_member", "birth_date");
        assertThat(columnType(jdbc, "pa_family_member", "age_value")).isEqualTo("smallint");
        assertThat(jdbc.queryForObject("""
            SELECT DATA_TYPE
            FROM information_schema.columns
            WHERE table_schema='personnel_archive'
              AND table_name='pa_person'
              AND column_name='delete_reason'
            """, String.class)).isEqualTo("longtext");
        Integer tableCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='personnel_archive' AND table_name NOT LIKE 'flyway%'", Integer.class);
        assertThat(tableCount).isEqualTo(15);
        var defaultAdmin = jdbc.queryForMap("""
            SELECT username,normalized_username,password_hash,display_name,
                   failed_login_count,locked_until
            FROM sys_user
            WHERE tenant_id=1 AND normalized_username='hr_6298bc833379'
            """);
        assertThat(defaultAdmin)
            .containsEntry("username", "hr_6298bc833379")
            .containsEntry("normalized_username", "hr_6298bc833379")
            .containsEntry("password_hash", DefaultAdminMigrationContractTest.DEFAULT_PASSWORD_HASH)
            .containsEntry("display_name", "人资部门")
            .containsEntry("failed_login_count", 0)
            .containsEntry("locked_until", null);
        assertThat(jdbc.queryForObject("""
            SELECT is_enabled + 0
            FROM sys_user
            WHERE tenant_id=1 AND normalized_username='hr_6298bc833379'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM sys_user_role ur
            JOIN sys_user u ON u.id=ur.user_id AND u.tenant_id=ur.tenant_id
            JOIN sys_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id
            WHERE u.normalized_username='hr_6298bc833379' AND r.role_code='ADMIN'
            """, Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO sys_user (tenant_id,username,normalized_username,password_hash,display_name) VALUES (1,'test_admin','test_admin','not-a-runtime-hash','测试管理员')");
        long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE normalized_username='test_admin'", Long.class);

        jdbc.update("""
            INSERT INTO pa_person
              (tenant_id,employee_no,person_name,normalized_name,birth_date,political_join_date,
               declaration_date,template_version,created_by,updated_by)
            VALUES (1,'T-HISTORY','历史记录','历史记录','二〇二四年','二〇二四年五月',
                    '二〇二四年五月三日','V1',?,?)
            """, userId, userId);
        long historicalPersonId = jdbc.queryForObject(
            "SELECT id FROM pa_person WHERE employee_no='T-HISTORY'", Long.class);
        jdbc.update("""
            INSERT INTO pa_employment
              (tenant_id,person_id,work_start_date,dispatched_to_bidding_company_date,
               title_or_skill_acquired_date)
            VALUES (1,?,'二〇二〇年五月三日','二〇二一年','二〇二二年六月')
            """, historicalPersonId);
        jdbc.update("""
            INSERT INTO pa_education
              (tenant_id,person_id,education_type,start_date,end_date,raw_text,sort_order)
            VALUES (1,?,'MAIN','二〇一〇年','二〇一四年六月','历史学历',1)
            """, historicalPersonId);
        jdbc.update("""
            INSERT INTO pa_work_experience
              (tenant_id,person_id,start_date,end_date,organization_and_position,raw_text,sort_order)
            VALUES (1,?,'二〇一五年七月','二〇一九年八月九日','历史单位','历史工作',1)
            """, historicalPersonId);
        jdbc.update("""
            INSERT INTO pa_reward_punishment
              (tenant_id,person_id,occurred_date,raw_text,sort_order)
            VALUES (1,?,'二〇二〇年九月十日','历史奖励',1)
            """, historicalPersonId);
        jdbc.update("""
            INSERT INTO pa_family_member
              (tenant_id,person_id,relationship_name,member_name,normalized_member_name,
               birth_date,age_text,raw_text,sort_order)
            VALUES (1,?,'妻子','历史成员','历史成员','二〇二四年五月三日','三十五岁','历史成员',1),
                   (1,?,'朋友','模糊年龄','模糊年龄',NULL,'三十多岁','模糊年龄',2)
            """, historicalPersonId, historicalPersonId);

        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("10")
            .load()
            .migrate();
        jdbc.update("""
            UPDATE pa_person
            SET birth_date='二〇二四.一.二',
                political_join_date='二〇二五/二',
                declaration_date='二〇二六-三-四',
                birth_date_year=1999,birth_date_month=1,birth_date_day=1,
                political_join_date_year=1999,political_join_date_month=1,
                political_join_date_day=1,
                declaration_date_year=1999,declaration_date_month=1,declaration_date_day=1
            WHERE id=?
            """, historicalPersonId);
        jdbc.update("""
            UPDATE pa_employment
            SET work_start_date='二〇二七.四.五',
                dispatched_to_bidding_company_date='二〇二八/五',
                title_or_skill_acquired_date='二〇二九-六-七',
                work_start_date_year=1999,work_start_date_month=1,work_start_date_day=1,
                dispatched_to_bidding_company_date_year=1999,
                dispatched_to_bidding_company_date_month=1,
                dispatched_to_bidding_company_date_day=1,
                title_or_skill_acquired_date_year=1999,
                title_or_skill_acquired_date_month=1,
                title_or_skill_acquired_date_day=1
            WHERE person_id=?
            """, historicalPersonId);
        jdbc.update("""
            UPDATE pa_education
            SET start_date='二〇三〇.七',end_date='二〇三一/八/九',
                start_date_year=1999,start_date_month=1,start_date_day=1,
                end_date_year=1999,end_date_month=1,end_date_day=1
            WHERE person_id=? AND education_type='MAIN'
            """, historicalPersonId);
        jdbc.update("""
            UPDATE pa_work_experience
            SET start_date='二〇三二-九-十',end_date='二〇三三.十',
                start_date_year=1999,start_date_month=1,start_date_day=1,
                end_date_year=1999,end_date_month=1,end_date_day=1
            WHERE person_id=?
            """, historicalPersonId);
        jdbc.update("""
            UPDATE pa_reward_punishment
            SET occurred_date='二〇三四/十一/十二',
                occurred_date_year=1999,occurred_date_month=1,occurred_date_day=1
            WHERE person_id=?
            """, historicalPersonId);
        jdbc.update("""
            UPDATE pa_family_member
            SET birth_date='二〇三五-十二-十三',
                birth_date_year=1999,birth_date_month=1,birth_date_day=1,
                age_value=7
            WHERE person_id=? AND sort_order=1
            """, historicalPersonId);
        jdbc.update("""
            UPDATE pa_family_member
            SET birth_date='二〇二四.十三.一',
                birth_date_year=1999,birth_date_month=1,birth_date_day=1,
                age_value=27
            WHERE person_id=? AND sort_order=2
            """, historicalPersonId);

        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("11")
            .load()
            .migrate();

        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,
                   political_join_date,political_join_date_year,political_join_date_month,
                   political_join_date_day,declaration_date,declaration_date_year,
                   declaration_date_month,declaration_date_day
            FROM pa_person WHERE id=?
            """, historicalPersonId))
            .containsEntry("birth_date", "二〇二四.一.二")
            .containsEntry("birth_date_year", 2024)
            .containsEntry("birth_date_month", 1)
            .containsEntry("birth_date_day", 2)
            .containsEntry("political_join_date", "二〇二五/二")
            .containsEntry("political_join_date_year", 2025)
            .containsEntry("political_join_date_month", 2)
            .containsEntry("political_join_date_day", null)
            .containsEntry("declaration_date", "二〇二六-三-四")
            .containsEntry("declaration_date_year", 2026)
            .containsEntry("declaration_date_month", 3)
            .containsEntry("declaration_date_day", 4);
        assertThat(jdbc.queryForMap("""
            SELECT work_start_date,work_start_date_year,work_start_date_month,work_start_date_day,
                   dispatched_to_bidding_company_date,
                   dispatched_to_bidding_company_date_year,dispatched_to_bidding_company_date_month,
                   dispatched_to_bidding_company_date_day,title_or_skill_acquired_date,
                   title_or_skill_acquired_date_year,title_or_skill_acquired_date_month,
                   title_or_skill_acquired_date_day
            FROM pa_employment WHERE person_id=?
            """, historicalPersonId))
            .containsEntry("work_start_date", "二〇二七.四.五")
            .containsEntry("work_start_date_year", 2027)
            .containsEntry("work_start_date_month", 4)
            .containsEntry("work_start_date_day", 5)
            .containsEntry("dispatched_to_bidding_company_date", "二〇二八/五")
            .containsEntry("dispatched_to_bidding_company_date_year", 2028)
            .containsEntry("dispatched_to_bidding_company_date_month", 5)
            .containsEntry("dispatched_to_bidding_company_date_day", null)
            .containsEntry("title_or_skill_acquired_date", "二〇二九-六-七")
            .containsEntry("title_or_skill_acquired_date_year", 2029)
            .containsEntry("title_or_skill_acquired_date_month", 6)
            .containsEntry("title_or_skill_acquired_date_day", 7);
        assertThat(jdbc.queryForMap("""
            SELECT start_date,end_date,start_date_year,start_date_month,start_date_day,
                   end_date_year,end_date_month,end_date_day
            FROM pa_education WHERE person_id=? AND education_type='MAIN'
            """, historicalPersonId))
            .containsEntry("start_date", "二〇三〇.七")
            .containsEntry("start_date_year", 2030)
            .containsEntry("start_date_month", 7)
            .containsEntry("start_date_day", null)
            .containsEntry("end_date", "二〇三一/八/九")
            .containsEntry("end_date_year", 2031)
            .containsEntry("end_date_month", 8)
            .containsEntry("end_date_day", 9);
        assertThat(jdbc.queryForMap("""
            SELECT start_date,start_date_year,start_date_month,start_date_day,
                   end_date,end_date_year,end_date_month,end_date_day
            FROM pa_work_experience WHERE person_id=?
            """, historicalPersonId))
            .containsEntry("start_date", "二〇三二-九-十")
            .containsEntry("start_date_year", 2032)
            .containsEntry("start_date_month", 9)
            .containsEntry("start_date_day", 10)
            .containsEntry("end_date", "二〇三三.十")
            .containsEntry("end_date_year", 2033)
            .containsEntry("end_date_month", 10)
            .containsEntry("end_date_day", null);
        assertThat(jdbc.queryForMap("""
            SELECT occurred_date,occurred_date_year,occurred_date_month,occurred_date_day
            FROM pa_reward_punishment WHERE person_id=?
            """, historicalPersonId))
            .containsEntry("occurred_date", "二〇三四/十一/十二")
            .containsEntry("occurred_date_year", 2034)
            .containsEntry("occurred_date_month", 11)
            .containsEntry("occurred_date_day", 12);
        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,age_text,age_value
            FROM pa_family_member WHERE person_id=? AND sort_order=1
            """, historicalPersonId))
            .containsEntry("birth_date", "二〇三五-十二-十三")
            .containsEntry("birth_date_year", 2035)
            .containsEntry("birth_date_month", 12)
            .containsEntry("birth_date_day", 13)
            .containsEntry("age_text", "三十五岁")
            .containsEntry("age_value", 7);
        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,age_text,age_value
            FROM pa_family_member WHERE person_id=? AND sort_order=2
            """, historicalPersonId))
            .containsEntry("birth_date", "二〇二四.十三.一")
            .containsEntry("birth_date_year", null)
            .containsEntry("birth_date_month", null)
            .containsEntry("birth_date_day", null)
            .containsEntry("age_text", "三十多岁")
            .containsEntry("age_value", 27);

        // V11 deliberately preserved source text. Make the deliberately invalid fixture blank before
        // advancing to V13; V13's separate integration test covers rejection without partial writes.
        jdbc.update("""
            UPDATE pa_family_member
            SET birth_date=NULL,age_text=NULL
            WHERE person_id=? AND sort_order=2
            """, historicalPersonId);
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .load()
            .migrate();

        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,
                   political_join_date,political_join_date_year,political_join_date_month,
                   political_join_date_day,declaration_date,declaration_date_year,
                   declaration_date_month,declaration_date_day
            FROM pa_person WHERE id=?
            """, historicalPersonId))
            .containsEntry("birth_date", "2024-01-02")
            .containsEntry("birth_date_year", 2024)
            .containsEntry("birth_date_month", 1)
            .containsEntry("birth_date_day", 2)
            .containsEntry("political_join_date", "2025-02")
            .containsEntry("political_join_date_year", 2025)
            .containsEntry("political_join_date_month", 2)
            .containsEntry("political_join_date_day", null)
            .containsEntry("declaration_date", "2026-03-04")
            .containsEntry("declaration_date_year", 2026)
            .containsEntry("declaration_date_month", 3)
            .containsEntry("declaration_date_day", 4);
        assertThat(jdbc.queryForMap("""
            SELECT work_start_date,dispatched_to_bidding_company_date,
                   title_or_skill_acquired_date
            FROM pa_employment WHERE person_id=?
            """, historicalPersonId))
            .containsEntry("work_start_date", "2027-04-05")
            .containsEntry("dispatched_to_bidding_company_date", "2028-05")
            .containsEntry("title_or_skill_acquired_date", "2029-06-07");
        assertThat(jdbc.queryForMap("""
            SELECT start_date,end_date,raw_text
            FROM pa_education WHERE person_id=? AND education_type='MAIN'
            """, historicalPersonId))
            .containsEntry("start_date", "2030-07")
            .containsEntry("end_date", "2031-08-09")
            .containsEntry("raw_text", "2030-07—2031-08-09\t历史学历");
        assertThat(jdbc.queryForMap("""
            SELECT start_date,end_date,raw_text
            FROM pa_work_experience WHERE person_id=?
            """, historicalPersonId))
            .containsEntry("start_date", "2032-09-10")
            .containsEntry("end_date", "2033-10")
            .containsEntry("raw_text", "2032-09-10—2033-10\t历史单位");
        assertThat(jdbc.queryForMap("""
            SELECT occurred_date,raw_text
            FROM pa_reward_punishment WHERE person_id=?
            """, historicalPersonId))
            .containsEntry("occurred_date", "2034-11-12")
            .containsEntry("raw_text", "2034-11-12\t历史奖励");
        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,
                   age_text,age_value,raw_text
            FROM pa_family_member WHERE person_id=? AND sort_order=1
            """, historicalPersonId))
            .containsEntry("birth_date", "2035-12-13")
            .containsEntry("birth_date_year", 2035)
            .containsEntry("birth_date_month", 12)
            .containsEntry("birth_date_day", 13)
            .containsEntry("age_text", "35")
            .containsEntry("age_value", 35)
            .containsEntry("raw_text", "妻子\t历史成员\t2035-12-13\t35\t\t");
        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,
                   age_text,age_value
            FROM pa_family_member WHERE person_id=? AND sort_order=2
            """, historicalPersonId))
            .containsEntry("birth_date", null)
            .containsEntry("birth_date_year", null)
            .containsEntry("birth_date_month", null)
            .containsEntry("birth_date_day", null)
            .containsEntry("age_text", null)
            .containsEntry("age_value", null);
        assertThat(jdbc.queryForObject("""
            SELECT DATA_TYPE
            FROM information_schema.columns
            WHERE table_schema='personnel_archive'
              AND table_name='pa_import_item'
              AND column_name='result_message'
            """, String.class)).isEqualTo("longtext");
        assertThat(jdbc.queryForObject(
            "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",
            Integer.class)).isEqualTo(14);

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE pa_person
            SET birth_date_year=NULL,birth_date_month=5,birth_date_day=NULL
            WHERE id=?
            """, historicalPersonId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
            UPDATE pa_family_member SET age_value=-1 WHERE person_id=? AND sort_order=1
            """, historicalPersonId)).isInstanceOf(DataAccessException.class);

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
            SET birth_date='1990-02',age_text='36'
            WHERE person_id=?
            """, activePersonId);
        assertThat(jdbc.queryForMap("SELECT birth_date FROM pa_person WHERE id=?", activePersonId))
            .containsEntry("birth_date", "1988");
        assertThat(jdbc.queryForMap(
            "SELECT birth_date,age_text FROM pa_family_member WHERE person_id=?", activePersonId))
            .containsEntry("birth_date", "1990-02")
            .containsEntry("age_text", "36");
    }

    private static void assertProjectionColumns(JdbcTemplate jdbc, String table, String... sourceColumns) {
        for (String sourceColumn : sourceColumns) {
            assertThat(columnType(jdbc, table, sourceColumn + "_year")).isEqualTo("smallint");
            assertThat(columnType(jdbc, table, sourceColumn + "_month")).isEqualTo("tinyint");
            assertThat(columnType(jdbc, table, sourceColumn + "_day")).isEqualTo("tinyint");
        }
    }

    private static String columnType(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
            SELECT DATA_TYPE
            FROM information_schema.columns
            WHERE table_schema='personnel_archive' AND table_name=? AND column_name=?
            """, String.class, table, column);
    }
}
