package com.company.personnelarchive.migration;

import com.company.personnelarchive.archiveimport.SourceDate;
import com.company.personnelarchive.archiveimport.SourceValueNormalizationException;
import com.company.personnelarchive.archiveimport.SourceValueNormalizerV3;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Flyway V13 的数据库无关标准文本回填。
 *
 * <p>先扫描并验证全部有效快照，再执行更新；因此模糊历史值会以表、记录和字段定位
 * 阻止迁移，而不会被猜测成数值或留下已转换一半的业务数据。已软删除快照保留历史
 * 内容且不参与正常页面和导出，故不会阻塞当前数据升级。</p>
 */
public final class CanonicalSourceValueBackfillV3 {
    private static final int BATCH_SIZE = 500;
    private static final Set<String> PRESENT_MARKERS = Set.of("今", "现在", "至今");

    private CanonicalSourceValueBackfillV3() {}

    public static void migrate(Connection connection) throws SQLException {
        validateDates(connection, "pa_person", null,
            date("birth_date"), date("political_join_date"), date("declaration_date"));
        validateDates(connection, "pa_employment", "person_id",
            date("work_start_date"), date("dispatched_to_bidding_company_date"),
            date("title_or_skill_acquired_date"));
        validateDates(connection, "pa_education", "person_id",
            date("start_date"), presentDate("end_date"));
        validateDates(connection, "pa_work_experience", "person_id",
            date("start_date"), presentDate("end_date"));
        validateDates(connection, "pa_reward_punishment", "person_id", date("occurred_date"));
        validateDates(connection, "pa_family_member", "person_id", date("birth_date"));
        validateAges(connection);

        updateDates(connection, "pa_person", null,
            date("birth_date"), date("political_join_date"), date("declaration_date"));
        updateDates(connection, "pa_employment", "person_id",
            date("work_start_date"), date("dispatched_to_bidding_company_date"),
            date("title_or_skill_acquired_date"));
        updateDates(connection, "pa_education", "person_id",
            date("start_date"), presentDate("end_date"));
        updateDates(connection, "pa_work_experience", "person_id",
            date("start_date"), presentDate("end_date"));
        updateDates(connection, "pa_reward_punishment", "person_id", date("occurred_date"));
        updateDates(connection, "pa_family_member", "person_id", date("birth_date"));
        updateAges(connection);

        rebuildEducationRawText(connection);
        rebuildWorkRawText(connection);
        rebuildRewardRawText(connection);
        rebuildFamilyRawText(connection);
    }

    private static void validateDates(
        Connection connection, String table, String personColumn, DateColumn... columns
    ) throws SQLException {
        String selected = Arrays.stream(columns).map(DateColumn::name).collect(Collectors.joining(","));
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(selectSql(table, personColumn, selected))) {
            while (rows.next()) {
                long id = rows.getLong("id");
                for (DateColumn column : columns) {
                    canonicalDate(table, id, column, rows.getString(column.name()));
                }
            }
        }
    }

    private static void validateAges(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(selectSql(
                 "pa_family_member", "person_id", "age_text"))) {
            while (rows.next()) canonicalAge(rows.getLong("id"), rows.getString("age_text"));
        }
    }

    private static void updateDates(
        Connection connection, String table, String personColumn, DateColumn... columns
    ) throws SQLException {
        String selected = Arrays.stream(columns).map(DateColumn::name).collect(Collectors.joining(","));
        String assignments = Arrays.stream(columns)
            .flatMap(column -> java.util.stream.Stream.of(
                column.name() + "=?", column.name() + "_year=?",
                column.name() + "_month=?", column.name() + "_day=?"))
            .collect(Collectors.joining(","));
        String updateSql = "UPDATE " + table + " SET " + assignments + " WHERE id=?";

        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery(selectSql(table, personColumn, selected));
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            int pending = 0;
            while (rows.next()) {
                int parameter = 1;
                long id = rows.getLong("id");
                for (DateColumn column : columns) {
                    CanonicalDate value = canonicalDate(table, id, column, rows.getString(column.name()));
                    setString(update, parameter++, value.text());
                    setSmallInt(update, parameter++, value.year());
                    setSmallInt(update, parameter++, value.month());
                    setSmallInt(update, parameter++, value.day());
                }
                update.setLong(parameter, id);
                update.addBatch();
                if (++pending == BATCH_SIZE) {
                    update.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) update.executeBatch();
        }
    }

    private static void updateAges(Connection connection) throws SQLException {
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery(selectSql(
                 "pa_family_member", "person_id", "age_text"));
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE pa_family_member SET age_text=?,age_value=? WHERE id=?")) {
            int pending = 0;
            while (rows.next()) {
                long id = rows.getLong("id");
                CanonicalAge age = canonicalAge(id, rows.getString("age_text"));
                setString(update, 1, age.text());
                setSmallInt(update, 2, age.value());
                update.setLong(3, id);
                update.addBatch();
                if (++pending == BATCH_SIZE) {
                    update.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) update.executeBatch();
        }
    }

    private static void rebuildEducationRawText(Connection connection) throws SQLException {
        rebuildRangeRawText(connection, "pa_education", "person_id", "raw_text", null,
            "education_type='MAIN'");
    }

    private static void rebuildWorkRawText(Connection connection) throws SQLException {
        rebuildRangeRawText(connection, "pa_work_experience", "person_id", "raw_text",
            "organization_and_position", null);
    }

    private static void rebuildRangeRawText(
        Connection connection, String table, String personColumn, String rawColumn,
        String rightColumn, String extraCondition
    ) throws SQLException {
        String selected = "start_date,end_date," + rawColumn
            + (rightColumn == null ? "" : "," + rightColumn);
        String sql = selectSql(table, personColumn, selected);
        if (extraCondition != null) sql = sql.replace(" ORDER BY", " AND " + extraCondition + " ORDER BY");
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery(sql);
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE " + table + " SET " + rawColumn + "=? WHERE id=?")) {
            int pending = 0;
            while (rows.next()) {
                String start = blankToNull(rows.getString("start_date"));
                String end = blankToNull(rows.getString("end_date"));
                String original = rows.getString(rawColumn);
                String left = rangeText(start, end);
                String right = rightColumn == null
                    ? rawRangeTail(original, left)
                    : nullToEmpty(rows.getString(rightColumn));
                setString(update, 1, combine(left, right, original));
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

    private static void rebuildRewardRawText(Connection connection) throws SQLException {
        String sql = selectSql(
            "pa_reward_punishment", "person_id", "occurred_date,raw_text");
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery(sql);
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE pa_reward_punishment SET raw_text=? WHERE id=?")) {
            int pending = 0;
            while (rows.next()) {
                String date = blankToNull(rows.getString("occurred_date"));
                String original = rows.getString("raw_text");
                String right = rawDateTail(original, date);
                setString(update, 1, combine(nullToEmpty(date), right, original));
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

    private static void rebuildFamilyRawText(Connection connection) throws SQLException {
        String sql = selectSql("pa_family_member", "person_id", """
            relationship_name,member_name,birth_date,age_text,political_status,organization_and_position
            """.replace("\n", "").strip());
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery(sql);
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE pa_family_member SET raw_text=? WHERE id=?")) {
            int pending = 0;
            while (rows.next()) {
                String raw = String.join("\t",
                    nullToEmpty(rows.getString("relationship_name")),
                    nullToEmpty(rows.getString("member_name")),
                    nullToEmpty(rows.getString("birth_date")),
                    nullToEmpty(rows.getString("age_text")),
                    nullToEmpty(rows.getString("political_status")),
                    nullToEmpty(rows.getString("organization_and_position")));
                update.setString(1, raw);
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

    private static CanonicalDate canonicalDate(
        String table, long id, DateColumn column, String raw
    ) throws SQLException {
        String value = blankToNull(raw);
        if (value == null) return CanonicalDate.EMPTY;
        if (column.allowPresent() && PRESENT_MARKERS.contains(value)) {
            return new CanonicalDate("至今", null, null, null);
        }
        try {
            SourceDate normalized = SourceValueNormalizerV3.normalizeDate(value);
            return new CanonicalDate(normalized.text(),
                normalized.year() == null ? null : normalized.year().getValue(),
                normalized.yearMonth() == null ? null : normalized.yearMonth().getMonthValue(),
                normalized.exactDate() == null ? null : normalized.exactDate().getDayOfMonth());
        } catch (SourceValueNormalizationException exception) {
            throw invalid(table, id, column.name());
        }
    }

    private static CanonicalAge canonicalAge(long id, String raw) throws SQLException {
        if (raw == null || raw.isBlank()) return CanonicalAge.EMPTY;
        try {
            var normalized = SourceValueNormalizerV3.normalizeAge(raw);
            return new CanonicalAge(normalized.text(), normalized.value());
        } catch (SourceValueNormalizationException exception) {
            throw invalid("pa_family_member", id, "age_text");
        }
    }

    private static String selectSql(String table, String personColumn, String selectedColumns) {
        String qualifiedColumns = Arrays.stream(selectedColumns.split(","))
            .map(String::strip)
            .map(column -> "t." + column + " AS " + column)
            .collect(Collectors.joining(","));
        String prefix = personColumn == null
            ? "SELECT t.id," + qualifiedColumns + " FROM " + table + " t WHERE t.is_deleted=0"
            : "SELECT t.id," + qualifiedColumns + " FROM " + table
                + " t JOIN pa_person p ON p.id=t." + personColumn
                + " AND p.tenant_id=t.tenant_id WHERE p.is_deleted=0";
        return prefix + " ORDER BY t.id";
    }

    private static String rangeText(String start, String end) throws SQLException {
        if (start == null && end == null) return "";
        if (start == null) throw new SQLException("date range has an end without a start");
        if (end == null) return start;
        return "至今".equals(end) ? start + "至今" : start + "—" + end;
    }

    private static String rawRangeTail(String raw, String canonicalRange) {
        if (raw == null || raw.isEmpty()) return "";
        int separator = raw.indexOf('\t');
        if (separator >= 0) return raw.substring(separator + 1);
        if (canonicalRange == null || canonicalRange.isEmpty()) return raw;
        try {
            return canonicalRange.equals(SourceValueNormalizerV3.normalizeDateRange(raw).text()) ? "" : raw;
        } catch (SourceValueNormalizationException exception) {
            return raw;
        }
    }

    private static String rawDateTail(String raw, String canonicalDate) {
        if (raw == null || raw.isEmpty()) return "";
        int separator = raw.indexOf('\t');
        if (separator >= 0) return raw.substring(separator + 1);
        if (canonicalDate == null || canonicalDate.isEmpty()) return raw;
        try {
            return canonicalDate.equals(SourceValueNormalizerV3.normalizeDate(raw).text()) ? "" : raw;
        } catch (SourceValueNormalizationException exception) {
            return raw;
        }
    }

    private static String combine(String left, String right, String original) {
        String safeLeft = nullToEmpty(left);
        String safeRight = nullToEmpty(right);
        if (safeLeft.isEmpty() && safeRight.isEmpty()) return original == null ? null : "";
        return safeLeft.isEmpty() ? safeRight : safeRight.isEmpty() ? safeLeft : safeLeft + "\t" + safeRight;
    }

    private static SQLException invalid(String table, long id, String field) {
        return new SQLException(table + " id=" + id + " field=" + field + " cannot be canonicalized");
    }

    private static void setSmallInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.SMALLINT);
        else statement.setInt(index, value);
    }

    private static void setString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static DateColumn date(String name) {
        return new DateColumn(name, false);
    }

    private static DateColumn presentDate(String name) {
        return new DateColumn(name, true);
    }

    private record DateColumn(String name, boolean allowPresent) {}

    private record CanonicalDate(String text, Integer year, Integer month, Integer day) {
        private static final CanonicalDate EMPTY = new CanonicalDate(null, null, null, null);
    }

    private record CanonicalAge(String text, Integer value) {
        private static final CanonicalAge EMPTY = new CanonicalAge(null, null);
    }
}
