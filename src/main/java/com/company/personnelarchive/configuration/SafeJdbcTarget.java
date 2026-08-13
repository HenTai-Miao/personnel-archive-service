/* 启动诊断只保留非秘密 MySQL 目标，不持有原始 JDBC URL 或其参数。 */
package com.company.personnelarchive.configuration;

import java.util.regex.Pattern;

record SafeJdbcTarget(String host, int port, String schema) {
    private static final Pattern MYSQL = Pattern.compile(
        "^jdbc:mysql://(?:\\[([0-9A-Fa-f:]+)]|([A-Za-z0-9_.-]+))(?::([0-9]{1,5}))?/([A-Za-z0-9_.-]+)(?:[?;][^\\r\\n]*)?$");

    static SafeJdbcTarget parse(String jdbcUrl) {
        if (jdbcUrl == null) return unknown();
        var match = MYSQL.matcher(jdbcUrl);
        if (!match.matches()) return unknown();
        String host = match.group(1) == null ? match.group(2) : match.group(1);
        int port;
        try {
            port = match.group(3) == null ? 3306 : Integer.parseInt(match.group(3));
        } catch (NumberFormatException exception) {
            return unknown();
        }
        if (port < 1 || port > 65_535) return unknown();
        return new SafeJdbcTarget(host, port, match.group(4));
    }

    static SafeJdbcTarget unknown() {
        return new SafeJdbcTarget("unknown", 0, "unknown");
    }
}
