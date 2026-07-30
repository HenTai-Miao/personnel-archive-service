package com.company.personnelarchive.archiveimport;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.util.Set;

import static com.company.personnelarchive.archiveimport.SourceValueNormalizationException.Kind.AGE_NOT_EXACT;
import static com.company.personnelarchive.archiveimport.SourceValueNormalizationException.Kind.DATE_NOT_EXACT;

/**
 * 当前导入使用的年龄和日期标准化边界。
 *
 * <p>V1/V2 继续作为冻结的解释规则返回来源文本；V3 只使用它们已经验证出的数字精度
 * 生成唯一存储文本，绝不补造来源中缺失的月或日。</p>
 */
public final class SourceValueNormalizerV3 {
    private static final Set<String> PRESENT_MARKERS = Set.of("今", "现在", "至今");

    private SourceValueNormalizerV3() {}

    public static SourceAge normalizeAge(String raw) {
        if (raw == null || raw.isBlank()) return new SourceAge("", null);
        SourceAge parsed = SourceValueParserV2.parseAge(raw);
        if (parsed.value() == null) throw new SourceValueNormalizationException(AGE_NOT_EXACT);
        return new SourceAge(Integer.toString(parsed.value()), parsed.value());
    }

    public static SourceDate normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) return SourceDate.empty();
        try {
            return canonical(SourceValueParserV2.parseDate(raw));
        } catch (DateTimeException | IllegalArgumentException exception) {
            throw new SourceValueNormalizationException(DATE_NOT_EXACT, exception);
        }
    }

    public static NormalizedDateRange normalizeDateRange(String raw) {
        if (raw == null || raw.isBlank()) return NormalizedDateRange.empty();
        SourceValueParserV2.DateRange parsed = SourceValueParserV2.parseDateRange(raw);
        if (parsed.start().text().isBlank()) throw new SourceValueNormalizationException(DATE_NOT_EXACT);

        SourceDate start = canonicalOrFail(parsed.start());
        SourceDate end;
        if (parsed.end().text().isBlank()) {
            end = SourceDate.empty();
        } else if (hasDateProjection(parsed.end())) {
            end = canonicalOrFail(parsed.end());
        } else if (PRESENT_MARKERS.contains(compact(parsed.end().text()))) {
            end = SourceDate.literal("至今");
        } else {
            throw new SourceValueNormalizationException(DATE_NOT_EXACT);
        }

        String text = end.text().isEmpty()
            ? start.text()
            : "至今".equals(end.text()) ? start.text() + "至今" : start.text() + "—" + end.text();
        return new NormalizedDateRange(start, end, text);
    }

    private static SourceDate canonicalOrFail(SourceDate parsed) {
        try {
            return canonical(parsed);
        } catch (IllegalArgumentException exception) {
            throw new SourceValueNormalizationException(DATE_NOT_EXACT, exception);
        }
    }

    private static SourceDate canonical(SourceDate parsed) {
        if (parsed.exactDate() != null) {
            return SourceDate.exact(parsed.exactDate().toString(), parsed.exactDate());
        }
        if (parsed.yearMonth() != null) {
            return SourceDate.month(parsed.yearMonth().toString(), parsed.yearMonth());
        }
        if (parsed.year() != null) {
            return SourceDate.year(parsed.year().toString(), parsed.year());
        }
        throw new IllegalArgumentException("date has no deterministic projection");
    }

    private static boolean hasDateProjection(SourceDate value) {
        return value.exactDate() != null || value.yearMonth() != null || value.year() != null;
    }

    private static String compact(String raw) {
        String normalized = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints()
            .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
            .forEach(result::appendCodePoint);
        return result.toString();
    }

    public record NormalizedDateRange(SourceDate start, SourceDate end, String text) {
        private static final NormalizedDateRange EMPTY =
            new NormalizedDateRange(SourceDate.empty(), SourceDate.empty(), "");

        public NormalizedDateRange {
            start = start == null ? SourceDate.empty() : start;
            end = end == null ? SourceDate.empty() : end;
            text = text == null ? "" : text;
        }

        public static NormalizedDateRange empty() {
            return EMPTY;
        }
    }
}
