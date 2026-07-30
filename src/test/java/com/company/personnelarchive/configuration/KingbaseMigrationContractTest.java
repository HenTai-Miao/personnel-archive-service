package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KingbaseMigrationContractTest {
    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/kingbase/V7__kingbase_initial_schema.sql");
    private static final Path SOURCE_VALUE_PROJECTIONS = Path.of(
        "src/main/resources/db/kingbase/V9__add_structured_source_value_projections.sql");
    private static final Path MIXED_DATE_BACKFILL = Path.of(
        "src/main/java/db/kingbase/V11__backfill_mixed_delimited_date_projections.java");
    private static final Path RESULT_MESSAGE_EXPANSION = Path.of(
        "src/main/resources/db/kingbase/V12__expand_import_result_message.sql");
    private static final Path CANONICAL_VALUE_BACKFILL = Path.of(
        "src/main/java/db/kingbase/V13__canonicalize_active_age_and_date_values.java");
    private static final Path OPEN_ENDED_RANGE_TEXT_BACKFILL = Path.of(
        "src/main/java/db/kingbase/V14__canonicalize_open_ended_range_text.java");
    private static final List<String> TABLES = List.of(
        "sys_tenant", "sys_user", "sys_role", "sys_user_role", "sys_user_session",
        "pa_person", "pa_employment", "pa_education", "pa_work_experience",
        "pa_reward_punishment", "pa_family_member", "pa_person_photo",
        "pa_import_batch", "pa_import_item", "sys_operation_log");

    @Test
    void definesTheFinalV7SchemaWithoutMysqlStorageSyntax() throws IOException {
        String sql = Files.readString(MIGRATION);

        TABLES.forEach(table -> assertThat(sql).contains("CREATE TABLE " + table));
        assertThat(sql)
            .contains("id_card_ciphertext BYTEA NULL")
            .contains("id_card_hmac BYTEA NULL")
            .contains("refresh_token_hash BYTEA NOT NULL")
            .contains("file_sha256 BYTEA NOT NULL")
            .contains("photo_data BYTEA NOT NULL")
            .contains("sha256 BYTEA NOT NULL")
            .contains("delete_reason TEXT NULL")
            .contains("age_text TEXT NULL")
            .contains("WHERE is_deleted = 0")
            .doesNotContain("ENGINE=InnoDB")
            .doesNotContain("CHARSET=")
            .doesNotContain("COLLATE=utf8mb4")
            .doesNotContain("AUTO_INCREMENT")
            .doesNotContain("DATETIME(")
            .doesNotContain("MEDIUMBLOB")
            .doesNotContain("LONGTEXT");
    }

    @Test
    void preservesEverySourceTextDateAndFinalBusinessConstraint() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertColumnsAreVarchar64(tableBlock(sql, "pa_person"),
            "birth_date", "political_join_date", "declaration_date");
        assertColumnsAreVarchar64(tableBlock(sql, "pa_employment"),
            "work_start_date", "dispatched_to_bidding_company_date",
            "title_or_skill_acquired_date");
        assertColumnsAreVarchar64(tableBlock(sql, "pa_education"), "start_date", "end_date");
        assertColumnsAreVarchar64(tableBlock(sql, "pa_work_experience"), "start_date", "end_date");
        assertColumnsAreVarchar64(tableBlock(sql, "pa_reward_punishment"), "occurred_date");
        assertColumnsAreVarchar64(tableBlock(sql, "pa_family_member"), "birth_date");
        assertThat(tableBlock(sql, "pa_family_member"))
            .contains("CONSTRAINT ck_pa_family_member_order CHECK (sort_order >= 1)");
    }

    @Test
    void preservesSecuritySeedsActiveUniquenessAndPhotoAnchors() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
            .contains("CREATE UNIQUE INDEX uk_pa_person_active_employee_no")
            .contains("WHERE is_deleted = 0 AND NULLIF(BTRIM(employee_no), '') IS NOT NULL")
            .contains("CREATE UNIQUE INDEX uk_pa_person_active_id_card_hmac")
            .contains("WHERE is_deleted = 0 AND id_card_hmac IS NOT NULL")
            .contains("anchor_type VARCHAR(32) NOT NULL DEFAULT 'MOVE_AND_RESIZE'")
            .contains("CONSTRAINT ck_pa_person_photo_anchor_area")
            .contains("CONSTRAINT ck_pa_person_photo_anchor_type")
            .contains("(1, 'ADMIN', '系统管理员'")
            .contains("(1, 'ARCHIVE_EDITOR', '档案编辑员'")
            .contains("(1, 'VIEWER', '档案查看员'")
            .contains("ALTER TABLE sys_tenant ALTER COLUMN id RESTART WITH 2")
            .contains("ALTER TABLE sys_role ALTER COLUMN id RESTART WITH 4");
    }

    @Test
    void addsNullableStructuredSourceValueProjectionsWithPortableConstraints() throws IOException {
        String sql = Files.readString(SOURCE_VALUE_PROJECTIONS);

        assertProjectionColumns(sql, "birth_date", "political_join_date", "declaration_date",
            "work_start_date", "dispatched_to_bidding_company_date", "title_or_skill_acquired_date",
            "start_date", "end_date", "occurred_date");
        assertThat(sql)
            .contains("age_value SMALLINT NULL")
            .contains("CONSTRAINT ck_pa_person_birth_date_parts CHECK")
            .contains("CONSTRAINT ck_pa_family_member_age_value CHECK (age_value IS NULL OR age_value >= 0)")
            .doesNotContain("TINYINT")
            .doesNotContain("ENGINE=InnoDB")
            .doesNotContain("AUTO_INCREMENT");
    }

    @Test
    void exposesTheKingbaseV10JavaBackfillAtTheConfiguredClasspathLocation() {
        var migration = new db.kingbase.V10__backfill_structured_source_values();

        assertThat(migration.getVersion().toString()).isEqualTo("10");
        assertThat(migration.getChecksum()).isEqualTo(10_001);
    }

    @Test
    void exposesTheKingbaseV11JavaBackfillAtTheConfiguredClasspathLocation() throws IOException {
        var migration = new db.kingbase.V11__backfill_mixed_delimited_date_projections();
        String source = Files.readString(MIXED_DATE_BACKFILL);

        assertThat(migration.getVersion().toString()).isEqualTo("11");
        assertThat(migration.getChecksum()).isEqualTo(11_001);
        assertThat(source).contains(
            "StructuredSourceValueBackfillV2.migrate(context.getConnection())");
    }

    @Test
    void expandsPersistentImportMessagesToKingbaseText() throws IOException {
        assertThat(Files.readString(RESULT_MESSAGE_EXPANSION))
            .contains("ALTER TABLE pa_import_item")
            .contains("ALTER COLUMN result_message TYPE TEXT")
            .doesNotContain("LONGTEXT");
    }

    @Test
    void exposesTheKingbaseV13CanonicalBackfillAtTheConfiguredClasspathLocation() throws IOException {
        var migration = new db.kingbase.V13__canonicalize_active_age_and_date_values();
        String source = Files.readString(CANONICAL_VALUE_BACKFILL);

        assertThat(migration.getVersion().toString()).isEqualTo("13");
        assertThat(migration.getChecksum()).isEqualTo(13_001);
        assertThat(source).contains("CanonicalSourceValueBackfillV3.migrate(context.getConnection())");
    }

    @Test
    void exposesTheKingbaseV14OpenEndedRangeBackfillAtTheConfiguredClasspathLocation()
        throws IOException, ReflectiveOperationException {
        assertThat(Files.exists(OPEN_ENDED_RANGE_TEXT_BACKFILL)).isTrue();
        String source = Files.readString(OPEN_ENDED_RANGE_TEXT_BACKFILL);
        Object migration = Class.forName("db.kingbase.V14__canonicalize_open_ended_range_text")
            .getDeclaredConstructor().newInstance();

        assertThat(migration).isInstanceOf(org.flywaydb.core.api.migration.BaseJavaMigration.class);
        var javaMigration = (org.flywaydb.core.api.migration.BaseJavaMigration) migration;
        assertThat(javaMigration.getVersion().toString()).isEqualTo("14");
        assertThat(javaMigration.getChecksum()).isEqualTo(14_001);
        assertThat(source).contains("OpenEndedRangeTextBackfillV4.migrate(context.getConnection())");
    }

    private static void assertColumnsAreVarchar64(String table, String... columns) {
        for (String column : columns) {
            assertThat(table).contains(column + " VARCHAR(64) NULL");
        }
    }

    private static void assertProjectionColumns(String sql, String... sourceColumns) {
        for (String sourceColumn : sourceColumns) {
            assertThat(sql)
                .contains(sourceColumn + "_year SMALLINT NULL")
                .contains(sourceColumn + "_month SMALLINT NULL")
                .contains(sourceColumn + "_day SMALLINT NULL");
        }
    }

    private static String tableBlock(String sql, String table) {
        String startToken = "CREATE TABLE " + table;
        int start = sql.indexOf(startToken);
        int end = sql.indexOf(";", start);
        assertThat(start).as("table %s exists", table).isGreaterThanOrEqualTo(0);
        assertThat(end).as("table %s ends with semicolon", table).isGreaterThan(start);
        return sql.substring(start, end + 1);
    }
}
