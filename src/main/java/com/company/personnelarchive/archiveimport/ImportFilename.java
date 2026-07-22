/* 上传文件名边界：只保留安全 basename，避免客户端路径进入数据库、响应或日志。 */
package com.company.personnelarchive.archiveimport;

final class ImportFilename {
    private static final String UNNAMED = "unnamed.xlsx";

    private ImportFilename() {}

    static String safe(String filename) {
        if (filename == null) return UNNAMED;
        String normalizedSeparators = filename.trim().replace('\\', '/');
        int lastSeparator = normalizedSeparators.lastIndexOf('/');
        String basename = normalizedSeparators.substring(lastSeparator + 1)
            .replaceAll("[\\p{Cntrl}]", "_").trim();
        if (basename.isBlank()) return UNNAMED;
        return basename.substring(0, Math.min(basename.length(), 255));
    }
}
