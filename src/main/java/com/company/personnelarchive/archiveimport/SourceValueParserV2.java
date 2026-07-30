package com.company.personnelarchive.archiveimport;

import cn.hutool.core.convert.NumberChineseFormatter;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 当前导入使用的来源年龄和日期 V2 解释边界。
 *
 * <p>年龄和 V1 已支持的单值日期先交给冻结的 V1；本版本补充中阿数字配合点、斜杠或
 * 横线分隔的日期，并在原字符串上切分日期区间。识别副本绝不能替代返回对象中的来源
 * 原文。</p>
 */
public final class SourceValueParserV2 {
    private static final String CHINESE_COUNT =
        "零〇○一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟萬億兩";
    private static final String NUMBER = "[0-9" + CHINESE_COUNT + "]+";
    private static final Pattern DELIMITED_FULL_DATE = Pattern.compile(
        "^(" + NUMBER + ")(?:年)?[./-](" + NUMBER + ")(?:月)?[./-](" + NUMBER + ")(?:日)?$");
    private static final Pattern DELIMITED_MONTH = Pattern.compile(
        "^(" + NUMBER + ")(?:年)?[./-](" + NUMBER + ")(?:月)?$");
    private static final Pattern ASCII_INTEGER = Pattern.compile("^\\d+$");
    private static final Pattern CHINESE_INTEGER = Pattern.compile("^[" + CHINESE_COUNT + "]+$");
    private static final Pattern POSITIONAL_YEAR = Pattern.compile(
        "^[0-9零〇○一二三四五六七八九壹贰叁肆伍陆柒捌玖]{4}$");
    private static final Pattern OPEN_ENDED_RANGE = Pattern.compile("至[\\s\\p{Zs}]*今$");
    private static final Set<String> PRESENT_MARKERS = Set.of("今", "现在", "至今");

    private SourceValueParserV2() {}

    public static SourceAge parseAge(String raw) {
        return SourceValueParserV1.parseAge(raw);
    }

    public static SourceDate parseDate(String raw) {
        try {
            return SourceValueParserV1.parseDate(raw);
        } catch (DateTimeException legacyFailure) {
            return parseMixedDelimitedDate(raw, legacyFailure);
        }
    }

    public static DateRange parseDateRange(String raw) {
        if (raw == null || raw.isBlank()) return DateRange.empty();

        String source = raw.strip();
        SourceDate single = tryDate(source);
        if (single != null) return new DateRange(single, SourceDate.empty());

        var openEnded = OPEN_ENDED_RANGE.matcher(source);
        if (openEnded.find()) {
            SourceDate start = tryDate(source.substring(0, openEnded.start()).strip());
            if (start != null) {
                return new DateRange(start, SourceDate.literal(source.substring(openEnded.start()).strip()));
            }
        }

        List<DateRange> candidates = new ArrayList<>();
        for (int index = 1; index < source.length() - 1; index++) {
            if (!isRangeSeparator(source.charAt(index))) continue;
            SourceDate start = tryDate(source.substring(0, index).strip());
            String right = source.substring(index + 1).strip();
            SourceDate end = PRESENT_MARKERS.contains(normalized(right))
                ? SourceDate.literal(right)
                : tryDate(right);
            if (start != null && end != null) candidates.add(new DateRange(start, end));
        }
        return candidates.size() == 1 ? candidates.getFirst() : DateRange.empty();
    }

    private static SourceDate parseMixedDelimitedDate(String raw, DateTimeException legacyFailure) {
        String text = raw == null ? "" : raw;
        String value = normalized(text);
        try {
            var full = DELIMITED_FULL_DATE.matcher(value);
            if (full.matches()) {
                return SourceDate.exact(text, LocalDate.of(
                    parseYear(full.group(1)), parseCountAsInt(full.group(2)), parseCountAsInt(full.group(3))));
            }
            var month = DELIMITED_MONTH.matcher(value);
            if (month.matches()) {
                return SourceDate.month(text, YearMonth.of(
                    parseYear(month.group(1)), parseCountAsInt(month.group(2))));
            }
        } catch (DateTimeException | IllegalArgumentException | ArithmeticException exception) {
            throw invalidDate(exception);
        }
        throw invalidDate(legacyFailure);
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

    private static int parseCountAsInt(String raw) {
        return Math.toIntExact(parseCount(raw));
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

    private static boolean isRangeSeparator(char value) {
        String normalized = Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFKC);
        return normalized.length() == 1 && switch (normalized.charAt(0)) {
            case '-', '—', '–', '~', '至' -> true;
            default -> false;
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
