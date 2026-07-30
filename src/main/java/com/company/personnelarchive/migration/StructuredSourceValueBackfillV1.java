package com.company.personnelarchive.migration;

import com.company.personnelarchive.archiveimport.SourceAge;
import com.company.personnelarchive.archiveimport.SourceDate;
import com.company.personnelarchive.archiveimport.SourceValueParserV1;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.DateTimeException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Flyway V10 的数据库无关回填实现。
 *
 * <p>只读取既有来源字符串并写入 V9 新增的数字列，不修改来源值，也不记录业务内容。
 * 该类与 {@link SourceValueParserV1} 共同构成已经发布的迁移语义。</p>
 */
public final class StructuredSourceValueBackfillV1 {
    private static final int BATCH_SIZE = 500;

    private StructuredSourceValueBackfillV1() {}

    public static void migrate(Connection connection) throws SQLException {
        backfillDates(connection, "pa_person", "birth_date", "political_join_date", "declaration_date");
        backfillDates(connection, "pa_employment", "work_start_date",
            "dispatched_to_bidding_company_date", "title_or_skill_acquired_date");
        backfillDates(connection, "pa_education", "start_date", "end_date");
        backfillDates(connection, "pa_work_experience", "start_date", "end_date");
        backfillDates(connection, "pa_reward_punishment", "occurred_date");
        backfillDates(connection, "pa_family_member", "birth_date");
        backfillAges(connection);
    }

    private static void backfillDates(Connection connection, String table, String... sourceColumns)
        throws SQLException {
        String selectedColumns = String.join(",", sourceColumns);
        String assignments = Arrays.stream(sourceColumns)
            .flatMap(column -> java.util.stream.Stream.of(
                column + "_year=?", column + "_month=?", column + "_day=?"))
            .collect(Collectors.joining(","));
        String selectSql = "SELECT id," + selectedColumns + " FROM " + table + " ORDER BY id";
        String updateSql = "UPDATE " + table + " SET " + assignments + " WHERE id=?";

        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery(selectSql);
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            int pending = 0;
            while (rows.next()) {
                int parameter = 1;
                for (String sourceColumn : sourceColumns) {
                    DateParts parts = dateParts(rows.getString(sourceColumn));
                    setSmallInt(update, parameter++, parts.year());
                    setSmallInt(update, parameter++, parts.month());
                    setSmallInt(update, parameter++, parts.day());
                }
                update.setLong(parameter, rows.getLong("id"));
                update.addBatch();
                if (++pending == BATCH_SIZE) {
                    update.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) update.executeBatch();
        }
    }

    private static void backfillAges(Connection connection) throws SQLException {
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("SELECT id,age_text FROM pa_family_member ORDER BY id");
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE pa_family_member SET age_value=? WHERE id=?")) {
            int pending = 0;
            while (rows.next()) {
                SourceAge age = SourceValueParserV1.parseAge(rows.getString("age_text"));
                setSmallInt(update, 1, age.value());
                update.setLong(2, rows.getLong("id"));
                update.addBatch();
                if (++pending == BATCH_SIZE) {
                    update.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) update.executeBatch();
        }
    }

    private static DateParts dateParts(String raw) {
        if (raw == null || raw.isBlank()) return DateParts.EMPTY;
        try {
            SourceDate date = SourceValueParserV1.parseDate(raw);
            return new DateParts(
                date.year() == null ? null : date.year().getValue(),
                date.yearMonth() == null ? null : date.yearMonth().getMonthValue(),
                date.exactDate() == null ? null : date.exactDate().getDayOfMonth());
        } catch (DateTimeException exception) {
            return DateParts.EMPTY;
        }
    }

    private static void setSmallInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.SMALLINT);
        else statement.setInt(index, value);
    }

    private record DateParts(Integer year, Integer month, Integer day) {
        private static final DateParts EMPTY = new DateParts(null, null, null);
    }
}
