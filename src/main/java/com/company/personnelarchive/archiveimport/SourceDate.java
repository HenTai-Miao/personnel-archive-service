package com.company.personnelarchive.archiveimport;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;

/**
 * Excel 中日期类单元格的已解释值。
 *
 * <p>冻结的 V1/V2 解析器仍可用来源文本构造本对象；当前 V3 导入会把文本改成标准
 * 格式。exactDate、yearMonth 和 year 始终只表达来源实际具备的精度，不能补出缺失
 * 的月或日。</p>
 */
public record SourceDate(String text, LocalDate exactDate, YearMonth yearMonth, Year year) {
    private static final SourceDate EMPTY = new SourceDate("", null, null, null);

    public SourceDate {
        text = text == null ? "" : text;
        if (exactDate != null && yearMonth == null) yearMonth = YearMonth.from(exactDate);
        if (yearMonth != null && year == null) year = Year.from(yearMonth);
        if (text.isEmpty() && (exactDate != null || yearMonth != null || year != null)) {
            throw new IllegalArgumentException("non-empty date requires source text");
        }
    }

    public static SourceDate empty() {
        return EMPTY;
    }

    public static SourceDate exact(String text, LocalDate date) {
        if (date == null) return empty();
        return new SourceDate(text, date, YearMonth.from(date), Year.from(date));
    }

    public static SourceDate month(String text, YearMonth month) {
        return new SourceDate(text, null, month, month == null ? null : Year.from(month));
    }

    public static SourceDate year(String text, Year year) {
        return new SourceDate(text, null, null, year);
    }

    public static SourceDate literal(String text) {
        return text == null || text.isBlank() ? empty() : new SourceDate(text, null, null, null);
    }

    public boolean isAfter(LocalDate date) {
        if (exactDate != null) return exactDate.isAfter(date);
        if (yearMonth != null) return yearMonth.isAfter(YearMonth.from(date));
        return year != null && year.isAfter(Year.from(date));
    }
}
