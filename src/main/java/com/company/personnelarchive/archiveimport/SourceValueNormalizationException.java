package com.company.personnelarchive.archiveimport;

/** 年龄或日期无法安全转换为唯一标准值；异常本身不携带工作簿原文。 */
public final class SourceValueNormalizationException extends RuntimeException {
    private final Kind kind;

    public SourceValueNormalizationException(Kind kind) {
        this(kind, null);
    }

    public SourceValueNormalizationException(Kind kind, Throwable cause) {
        super(kind == Kind.AGE_NOT_EXACT ? "age is not exact" : "date is not exact", cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        AGE_NOT_EXACT,
        DATE_NOT_EXACT
    }
}
