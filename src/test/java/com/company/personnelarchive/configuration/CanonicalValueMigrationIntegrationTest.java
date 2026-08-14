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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CanonicalValueMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("personnel_archive")
        .withUsername("test_user")
        .withPassword("test_password");

    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabaseAtV12() {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway("12").migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @Test
    void v13CanonicalizesEveryActiveBusinessValueAndSkipsDeletedHistory() {
        long operatorId = defaultAdminId();
        long personId = insertPerson(operatorId, "MIG-ACTIVE", 0,
            "二〇二四.一.二", "二〇二五年五月", "二〇二六年");
        jdbc.update("""
            INSERT INTO pa_employment
              (tenant_id,person_id,work_start_date,dispatched_to_bidding_company_date,
               title_or_skill_acquired_date)
            VALUES (1,?,'二〇二〇年五月三日','二〇二一年','二〇二二年六月')
            """, personId);
        jdbc.update("""
            INSERT INTO pa_education
              (tenant_id,person_id,education_type,start_date,end_date,raw_text,sort_order)
            VALUES (1,?,'MAIN','二〇一〇年','二〇一四年六月',
                    '历史学历',1)
            """, personId);
        jdbc.update("""
            INSERT INTO pa_work_experience
              (tenant_id,person_id,start_date,end_date,organization_and_position,raw_text,sort_order)
            VALUES (1,?,'二〇一五年七月','二〇一九年八月九日','历史单位',
                    '二〇一五年七月至二〇一九年八月九日\t历史单位',1)
            """, personId);
        jdbc.update("""
            INSERT INTO pa_reward_punishment
              (tenant_id,person_id,occurred_date,raw_text,sort_order)
            VALUES (1,?,'二〇二〇.九.十','历史奖励',1)
            """, personId);
        jdbc.update("""
            INSERT INTO pa_family_member
              (tenant_id,person_id,relationship_name,member_name,normalized_member_name,
               birth_date,age_text,political_status,organization_and_position,raw_text,sort_order)
            VALUES (1,?,'妻子','历史成员','历史成员','二〇〇〇/七/二十三','三十五岁',
                    '群众','历史单位','旧的组合文本',1)
            """, personId);

        long deletedId = insertPerson(operatorId, "MIG-DELETED", 1,
            "二〇二四年春", null, null);
        jdbc.update("""
            INSERT INTO pa_family_member
              (tenant_id,person_id,relationship_name,member_name,normalized_member_name,
               age_text,raw_text,sort_order)
            VALUES (1,?,'朋友','历史模糊值','历史模糊值','三十多岁','历史模糊值',1)
            """, deletedId);

        flyway("13").migrate();

        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,
                   political_join_date,political_join_date_year,political_join_date_month,
                   political_join_date_day,declaration_date,declaration_date_year,
                   declaration_date_month,declaration_date_day
            FROM pa_person WHERE id=?
            """, personId))
            .containsEntry("birth_date", "2024-01-02")
            .containsEntry("birth_date_year", 2024)
            .containsEntry("birth_date_month", 1)
            .containsEntry("birth_date_day", 2)
            .containsEntry("political_join_date", "2025-05")
            .containsEntry("political_join_date_year", 2025)
            .containsEntry("political_join_date_month", 5)
            .containsEntry("political_join_date_day", null)
            .containsEntry("declaration_date", "2026")
            .containsEntry("declaration_date_year", 2026)
            .containsEntry("declaration_date_month", null)
            .containsEntry("declaration_date_day", null);
        assertThat(jdbc.queryForMap("""
            SELECT work_start_date,dispatched_to_bidding_company_date,title_or_skill_acquired_date
            FROM pa_employment WHERE person_id=?
            """, personId))
            .containsEntry("work_start_date", "2020-05-03")
            .containsEntry("dispatched_to_bidding_company_date", "2021")
            .containsEntry("title_or_skill_acquired_date", "2022-06");
        assertThat(jdbc.queryForMap("""
            SELECT start_date,end_date,raw_text FROM pa_education WHERE person_id=?
            """, personId))
            .containsEntry("start_date", "2010")
            .containsEntry("end_date", "2014-06")
            .containsEntry("raw_text", "2010—2014-06\t历史学历");
        assertThat(jdbc.queryForMap("""
            SELECT start_date,end_date,raw_text FROM pa_work_experience WHERE person_id=?
            """, personId))
            .containsEntry("start_date", "2015-07")
            .containsEntry("end_date", "2019-08-09")
            .containsEntry("raw_text", "2015-07—2019-08-09\t历史单位");
        assertThat(jdbc.queryForMap("""
            SELECT occurred_date,raw_text FROM pa_reward_punishment WHERE person_id=?
            """, personId))
            .containsEntry("occurred_date", "2020-09-10")
            .containsEntry("raw_text", "2020-09-10\t历史奖励");
        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,
                   age_text,age_value,raw_text
            FROM pa_family_member WHERE person_id=?
            """, personId))
            .containsEntry("birth_date", "2000-07-23")
            .containsEntry("birth_date_year", 2000)
            .containsEntry("birth_date_month", 7)
            .containsEntry("birth_date_day", 23)
            .containsEntry("age_text", "35")
            .containsEntry("age_value", 35)
            .containsEntry("raw_text", "妻子\t历史成员\t2000-07-23\t35\t群众\t历史单位");

        assertThat(jdbc.queryForMap("SELECT birth_date FROM pa_person WHERE id=?", deletedId))
            .containsEntry("birth_date", "二〇二四年春");
        assertThat(jdbc.queryForMap("SELECT age_text FROM pa_family_member WHERE person_id=?", deletedId))
            .containsEntry("age_text", "三十多岁");
    }

    @Test
    void v14CanonicalizesOpenEndedRangeTextForActiveSnapshotsOnly() {
        flyway("13").migrate();
        long operatorId = defaultAdminId();
        long activeId = insertPerson(operatorId, "MIG-V14-ACTIVE", 0, null, null, null);
        long deletedId = insertPerson(operatorId, "MIG-V14-DELETED", 1, null, null, null);

        insertOpenEndedEducation(activeId, "2010-09至今\t活动学历");
        insertOpenEndedWork(activeId, "2018-06至今\t活动单位");
        insertOpenEndedEducation(deletedId, "2010-09至今\t历史学历");
        insertOpenEndedWork(deletedId, "2018-06至今\t历史单位");

        Flyway v14 = flyway("14");
        v14.migrate();

        assertThat(jdbc.queryForMap("""
            SELECT start_date,end_date,start_date_year,start_date_month,start_date_day,
                   end_date_year,end_date_month,end_date_day,raw_text
            FROM pa_education WHERE person_id=? AND education_type='MAIN'
            """, activeId))
            .containsEntry("start_date", "2010-09")
            .containsEntry("end_date", "至今")
            .containsEntry("start_date_year", 2010)
            .containsEntry("start_date_month", 9)
            .containsEntry("start_date_day", null)
            .containsEntry("end_date_year", null)
            .containsEntry("end_date_month", null)
            .containsEntry("end_date_day", null)
            .containsEntry("raw_text", "2010-09—至今\t活动学历");
        assertThat(jdbc.queryForMap("""
            SELECT start_date,end_date,start_date_year,start_date_month,start_date_day,
                   end_date_year,end_date_month,end_date_day,raw_text
            FROM pa_work_experience WHERE person_id=?
            """, activeId))
            .containsEntry("start_date", "2018-06")
            .containsEntry("end_date", "至今")
            .containsEntry("start_date_year", 2018)
            .containsEntry("start_date_month", 6)
            .containsEntry("start_date_day", null)
            .containsEntry("end_date_year", null)
            .containsEntry("end_date_month", null)
            .containsEntry("end_date_day", null)
            .containsEntry("raw_text", "2018-06—至今\t活动单位");

        assertThat(jdbc.queryForObject(
            "SELECT raw_text FROM pa_education WHERE person_id=? AND education_type='MAIN'",
            String.class, deletedId)).isEqualTo("2010-09至今\t历史学历");
        assertThat(jdbc.queryForObject(
            "SELECT raw_text FROM pa_work_experience WHERE person_id=?",
            String.class, deletedId)).isEqualTo("2018-06至今\t历史单位");
        assertThat(v14.info().current().getVersion().toString()).isEqualTo("14");
    }

    @Test
    void v13RejectsAmbiguousActiveDataWithoutPartiallyUpdatingOtherFields() {
        long operatorId = defaultAdminId();
        long personId = insertPerson(operatorId, "MIG-INVALID", 0,
            "二〇二四年", null, null);
        jdbc.update("""
            INSERT INTO pa_family_member
              (tenant_id,person_id,relationship_name,member_name,normalized_member_name,
               age_text,raw_text,sort_order)
            VALUES (1,?,'朋友','模糊年龄','模糊年龄','三十多岁','模糊年龄',1)
            """, personId);

        assertThatThrownBy(() -> flyway("13").migrate())
            .isInstanceOf(FlywayException.class)
            .hasRootCauseMessage("pa_family_member id=1 field=age_text cannot be canonicalized");

        assertThat(jdbc.queryForMap("SELECT birth_date FROM pa_person WHERE id=?", personId))
            .containsEntry("birth_date", "二〇二四年");
        assertThat(jdbc.queryForMap("SELECT age_text FROM pa_family_member WHERE person_id=?", personId))
            .containsEntry("age_text", "三十多岁");
    }

    @Test
    void v12ExpandsPersistentImportMessages() {
        assertThat(jdbc.queryForObject("""
            SELECT DATA_TYPE FROM information_schema.columns
            WHERE table_schema='personnel_archive'
              AND table_name='pa_import_item' AND column_name='result_message'
            """, String.class)).isEqualTo("longtext");
    }

    private long insertPerson(
        long operatorId, String employeeNo, int deleted,
        String birthDate, String politicalJoinDate, String declarationDate
    ) {
        jdbc.update("""
            INSERT INTO pa_person
              (tenant_id,employee_no,person_name,normalized_name,birth_date,
               political_join_date,declaration_date,template_version,is_deleted,
               deleted_at,deleted_by,delete_reason,created_by,updated_by)
            VALUES (1,?,'迁移测试','迁移测试',?,?,?,'V1',?,
                    CASE WHEN ?=1 THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
                    CASE WHEN ?=1 THEN ? ELSE NULL END,
                    CASE WHEN ?=1 THEN '迁移测试历史' ELSE NULL END,?,?)
            """, employeeNo, birthDate, politicalJoinDate, declarationDate, deleted,
            deleted, deleted, operatorId, deleted, operatorId, operatorId);
        return jdbc.queryForObject("SELECT id FROM pa_person WHERE employee_no=?", Long.class, employeeNo);
    }

    private void insertOpenEndedEducation(long personId, String rawText) {
        jdbc.update("""
            INSERT INTO pa_education
              (tenant_id,person_id,education_type,start_date,end_date,
               start_date_year,start_date_month,raw_text,sort_order)
            VALUES (1,?,'MAIN','2010-09','至今',2010,9,?,1)
            """, personId, rawText);
    }

    private void insertOpenEndedWork(long personId, String rawText) {
        jdbc.update("""
            INSERT INTO pa_work_experience
              (tenant_id,person_id,start_date,end_date,start_date_year,start_date_month,
               organization_and_position,raw_text,sort_order)
            VALUES (1,?,'2018-06','至今',2018,6,'活动单位',?,1)
            """, personId, rawText);
    }

    private long defaultAdminId() {
        return jdbc.queryForObject(
            "SELECT id FROM sys_user WHERE normalized_username='hr_6298bc833379'", Long.class);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
