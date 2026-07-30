package com.company.personnelarchive.archiveimport;

import cn.hutool.core.convert.NumberChineseFormatter;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 中文年龄和日期来源值的 V1 解析边界。
 *
 * <p>该版本同时被 Flyway V10 历史回填调用，因此语义发布后不可修改；新规则必须新增
 * 解析器版本和迁移。Hutool 只负责带单位的中文整数，四位年份逐字解释、来源精度和
 * 日历合法性由本类固定。</p>
 */
public final class SourceValueParserV1 {
    private static final int MAX_AGE = Short.MAX_VALUE;
    private static final String CHINESE_COUNT =
        "零〇○一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟萬億兩";
    private static final Pattern AGE = Pattern.compile("^([0-9" + CHINESE_COUNT + "]+)(?:周岁|岁)?$");
    private static final Pattern CHINESE_FULL_DATE = Pattern.compile(
        "^([0-9" + CHINESE_COUNT + "]+)年([0-9" + CHINESE_COUNT + "]+)月([0-9" + CHINESE_COUNT + "]+)日$");
    private static final Pattern CHINESE_MONTH = Pattern.compile(
        "^([0-9" + CHINESE_COUNT + "]+)年([0-9" + CHINESE_COUNT + "]+)月$");
    private static final Pattern YEAR = Pattern.compile("^([0-9" + CHINESE_COUNT + "]+)(?:年)?$");
    private static final Pattern DELIMITED_FULL_DATE = Pattern.compile("^(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})$");
    private static final Pattern DELIMITED_MONTH = Pattern.compile("^(\\d{4})[./-](\\d{1,2})$");
    private static final Pattern ASCII_INTEGER = Pattern.compile("^\\d+$");
    private static final Pattern CHINESE_INTEGER = Pattern.compile("^[" + CHINESE_COUNT + "]+$");
    private static final Pattern POSITIONAL_YEAR = Pattern.compile(
        "^[0-9零〇○一二三四五六七八九壹贰叁肆伍陆柒捌玖]{4}$");
    private static final Set<String> PRESENT_MARKERS = Set.of("今", "现在", "至今");

    private SourceValueParserV1() {}

    public static SourceAge parseAge(String raw) {
        String text = raw == null ? "" : raw;
        String normalized = normalized(text);
        var matcher = AGE.matcher(normalized);
        if (!matcher.matches()) return new SourceAge(text, null);
        try {
            long value = parseCount(matcher.group(1));
            return value >= 0 && value <= MAX_AGE
                ? new SourceAge(text, Math.toIntExact(value))
                : new SourceAge(text, null);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return new SourceAge(text, null);
        }
    }

    public static SourceDate parseDate(String raw) {
        String text = raw == null ? "" : raw;
        String value = normalized(text);
        if (value.isEmpty()) return SourceDate.empty();
        try {
            var fullChinese = CHINESE_FULL_DATE.matcher(value);
            if (fullChinese.matches()) {
                int year = parseYear(fullChinese.group(1));
                int month = parseSmallCount(fullChinese.group(2));
                int day = parseSmallCount(fullChinese.group(3));
                return SourceDate.exact(text, LocalDate.of(year, month, day));
            }
            var monthChinese = CHINESE_MONTH.matcher(value);
            if (monthChinese.matches()) {
                return SourceDate.month(text, YearMonth.of(
                    parseYear(monthChinese.group(1)), parseSmallCount(monthChinese.group(2))));
            }
            var fullDelimited = DELIMITED_FULL_DATE.matcher(value);
            if (fullDelimited.matches()) {
                return SourceDate.exact(text, LocalDate.of(
                    Integer.parseInt(fullDelimited.group(1)), Integer.parseInt(fullDelimited.group(2)),
                    Integer.parseInt(fullDelimited.group(3))));
            }
            var monthDelimited = DELIMITED_MONTH.matcher(value);
            if (monthDelimited.matches()) {
                return SourceDate.month(text, YearMonth.of(
                    Integer.parseInt(monthDelimited.group(1)), Integer.parseInt(monthDelimited.group(2))));
            }
            var year = YEAR.matcher(value);
            if (year.matches()) return SourceDate.year(text, Year.of(parseYear(year.group(1))));
        } catch (DateTimeException | IllegalArgumentException | ArithmeticException exception) {
            throw invalidDate(exception);
        }
        throw invalidDate(null);
    }

    public static DateRange parseDateRange(String raw) {
        if (raw == null || raw.isBlank()) return DateRange.empty();
        String source = raw.strip();
        String compact = normalized(source);

        if (compact.endsWith("至今")) {
            SourceDate start = tryDate(compact.substring(0, compact.length() - 2));
            if (start != null) return new DateRange(start, SourceDate.literal("至今"));
        }

        List<DateRange> candidates = new ArrayList<>();
        for (int index = 1; index < compact.length() - 1; index++) {
            char separator = compact.charAt(index);
            if (separator != '-' && separator != '—' && separator != '–'
                && separator != '~' && separator != '至') continue;
            String left = compact.substring(0, index);
            String right = compact.substring(index + 1);
            SourceDate start = tryDate(left);
            SourceDate end = PRESENT_MARKERS.contains(right) ? SourceDate.literal(right) : tryDate(right);
            if (start != null && end != null) candidates.add(new DateRange(start, end));
        }
        if (candidates.size() == 1) return candidates.getFirst();

        SourceDate single = tryDate(source);
        return single == null ? DateRange.empty() : new DateRange(single, SourceDate.empty());
    }

    private static SourceDate tryDate(String raw) {
        try {
            SourceDate parsed = parseDate(raw);
            return parsed.text().isEmpty() ? null : parsed;
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static int parseYear(String raw) {
        String value = normalized(raw);
        long year;
        if (POSITIONAL_YEAR.matcher(value).matches()) {
            year = 0;
            for (int index = 0; index < value.length(); index++) {
                year = year * 10 + positionalDigit(value.charAt(index));
            }
        } else {
            year = parseCount(value);
        }
        if (year < 1000 || year > 9999) throw invalidDate(null);
        return Math.toIntExact(year);
    }

    private static int parseSmallCount(String raw) {
        long value = parseCount(raw);
        return Math.toIntExact(value);
    }

    private static long parseCount(String raw) {
        if (ASCII_INTEGER.matcher(raw).matches()) return Long.parseLong(raw);
        if (!CHINESE_INTEGER.matcher(raw).matches()) throw new IllegalArgumentException("unsupported integer");
        return NumberChineseFormatter.chineseToNumber(raw);
    }

    private static int positionalDigit(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        return switch (value) {
            case '零', '〇', '○' -> 0;
            case '一', '壹' -> 1;
            case '二', '贰' -> 2;
            case '三', '叁' -> 3;
            case '四', '肆' -> 4;
            case '五', '伍' -> 5;
            case '六', '陆' -> 6;
            case '七', '柒' -> 7;
            case '八', '捌' -> 8;
            case '九', '玖' -> 9;
            default -> throw new IllegalArgumentException("unsupported positional digit");
        };
    }

    private static String normalized(String raw) {
        String normalized = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC);
        StringBuilder compact = new StringBuilder(normalized.length());
        normalized.codePoints()
            .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
            .forEach(compact::appendCodePoint);
        return compact.toString();
    }

    private static DateTimeException invalidDate(Throwable cause) {
        return cause == null
            ? new DateTimeException("unsupported source date")
            : new DateTimeException("unsupported source date", cause);
    }

    public record DateRange(SourceDate start, SourceDate end) {
        private static final DateRange EMPTY = new DateRange(SourceDate.empty(), SourceDate.empty());

        public DateRange {
            start = start == null ? SourceDate.empty() : start;
            end = end == null ? SourceDate.empty() : end;
        }

        public static DateRange empty() {
            return EMPTY;
        }
    }
}
