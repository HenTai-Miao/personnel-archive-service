package com.company.personnelarchive.archiveimport;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;

/**
 * Excel 中日期类单元格的来源值。
 *
 * <p>文本是入库、展示和重建的唯一权威值。exactDate、yearMonth 和 year 只按来源实际
 * 精度用于合法性及未来日期校验，绝不能反向改写文本或补出缺失的月、日。</p>
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

    public boolean isAfter(LocalDate date) {
        if (exactDate != null) return exactDate.isAfter(date);
        if (yearMonth != null) return yearMonth.isAfter(YearMonth.from(date));
        return year != null && year.isAfter(Year.from(date));
    }
}
