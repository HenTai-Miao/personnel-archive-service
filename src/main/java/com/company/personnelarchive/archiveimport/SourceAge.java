package com.company.personnelarchive.archiveimport;

/** Excel 年龄来源值及其可确定的非负整数投影。 */
public record SourceAge(String text, Integer value) {
    public SourceAge {
        text = text == null ? "" : text;
        if (value != null && value < 0) throw new IllegalArgumentException("age must not be negative");
    }
}
