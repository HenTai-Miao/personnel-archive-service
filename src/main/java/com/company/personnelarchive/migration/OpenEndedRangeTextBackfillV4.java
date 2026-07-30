package com.company.personnelarchive.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Flyway V14 的数据库无关开放日期区间组合文本回填。
 *
 * <p>V13 已保证活动档案的起止日期和数字投影均为标准值。本迁移只为活动主要学历和
 * 工作经历的“至今”区间补齐可见连接符，不重新解释端点，也不触碰软删除历史。</p>
 */
public final class OpenEndedRangeTextBackfillV4 {
    private static final int BATCH_SIZE = 500;
    private static final String PRESENT = "至今";

    private OpenEndedRangeTextBackfillV4() {}

    public static void migrate(Connection connection) throws SQLException {
        rewriteEducation(connection);
        rewriteWork(connection);
    }

    private static void rewriteEducation(Connection connection) throws SQLException {
        String selectSql = """
            SELECT e.id,e.start_date,e.raw_text
            FROM pa_education e
            JOIN pa_person p ON p.id=e.person_id AND p.tenant_id=e.tenant_id
            WHERE p.is_deleted=0 AND e.education_type='MAIN' AND e.end_date=? AND e.id>?
            ORDER BY e.id
            LIMIT %d
            """.formatted(BATCH_SIZE);
        rewrite(connection, new RewriteSpec("pa_education", selectSql, true));
    }

    private static void rewriteWork(Connection connection) throws SQLException {
        String selectSql = """
            SELECT w.id,w.start_date,w.organization_and_position,w.raw_text
            FROM pa_work_experience w
            JOIN pa_person p ON p.id=w.person_id AND p.tenant_id=w.tenant_id
            WHERE p.is_deleted=0 AND w.end_date=? AND w.id>?
            ORDER BY w.id
            LIMIT %d
            """.formatted(BATCH_SIZE);
        rewrite(connection, new RewriteSpec("pa_work_experience", selectSql, false));
    }

    private static void rewrite(Connection connection, RewriteSpec spec) throws SQLException {
        String updateSql = "UPDATE " + spec.table() + " SET raw_text=? WHERE id=?";
        try (PreparedStatement select = connection.prepareStatement(spec.selectSql());
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            long afterId = 0;
            while (true) {
                select.setString(1, PRESENT);
                select.setLong(2, afterId);
                List<PendingUpdate> pending = new ArrayList<>(BATCH_SIZE);
                long pageLastId = afterId;
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        long id = rows.getLong("id");
                        pageLastId = id;
                        String range = canonicalRange(spec.table(), id, rows.getString("start_date"));
                        String right = spec.preserveRawTail()
                            ? rawTail(rows.getString("raw_text"))
                            : rows.getString("organization_and_position");
                        String canonical = combine(range, right);
                        if (canonical.equals(rows.getString("raw_text"))) continue;
                        pending.add(new PendingUpdate(id, canonical));
                    }
                }
                if (pageLastId == afterId) return;
                for (PendingUpdate row : pending) {
                    update.setString(1, row.rawText());
                    update.setLong(2, row.id());
                    update.addBatch();
                }
                if (!pending.isEmpty()) {
                    update.executeBatch();
                    update.clearBatch();
                }
                afterId = pageLastId;
            }
        }
    }

    private static String canonicalRange(String table, long id, String start) throws SQLException {
        if (start == null || start.isBlank()) {
            throw new SQLException(table + " id=" + id + " field=start_date cannot be canonicalized");
        }
        return start + "—" + PRESENT;
    }

    private static String rawTail(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int separator = raw.indexOf('\t');
        return separator < 0 ? "" : raw.substring(separator + 1);
    }

    private static String combine(String range, String right) {
        return right == null || right.isEmpty() ? range : range + "\t" + right;
    }

    private record RewriteSpec(String table, String selectSql, boolean preserveRawTail) {}

    private record PendingUpdate(long id, String rawText) {}
}
