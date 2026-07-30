package com.company.personnelarchive.migration;

import com.company.personnelarchive.archiveimport.SourceDate;
import com.company.personnelarchive.archiveimport.SourceValueParserV2;

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
 * Flyway V11 的数据库无关日期投影回填。
 *
 * <p>V1/V10 是冻结合同，因此本版本独立读取全部日期原文并以 V2 确定性重算投影。
 * 只更新年、月、日数字列，不修改来源文本、年龄或其他业务数据。</p>
 */
public final class StructuredSourceValueBackfillV2 {
    private static final int BATCH_SIZE = 500;

    private StructuredSourceValueBackfillV2() {}

    public static void migrate(Connection connection) throws SQLException {
        backfillDates(connection, "pa_person", "birth_date", "political_join_date", "declaration_date");
        backfillDates(connection, "pa_employment", "work_start_date",
            "dispatched_to_bidding_company_date", "title_or_skill_acquired_date");
        backfillDates(connection, "pa_education", "start_date", "end_date");
        backfillDates(connection, "pa_work_experience", "start_date", "end_date");
        backfillDates(connection, "pa_reward_punishment", "occurred_date");
        backfillDates(connection, "pa_family_member", "birth_date");
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

    private static DateParts dateParts(String raw) {
        if (raw == null || raw.isBlank()) return DateParts.EMPTY;
        try {
            SourceDate date = SourceValueParserV2.parseDate(raw);
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
