package com.company.personnelarchive.archiveimport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 当前导入边界的日期范围标准化规则。
 *
 * <p>V3 是历史迁移使用的冻结规则，不能原地修改。V4 兼容旧工作簿中明确的重复 ASCII
 * 连字符或中文长横线范围分隔符，并把已通过 V3 校验的开放区间统一为“起点—至今”。</p>
 */
public final class SourceValueNormalizerV4 {
    private static final int MAX_DATE_RANGE_LENGTH = 128;
    private static final Pattern LEGACY_REPEATED_SEPARATOR =
        Pattern.compile("(?<![-—])(?:-{2,3}|—{2})(?![-—])");

    private SourceValueNormalizerV4() {}

    public static SourceValueNormalizerV3.NormalizedDateRange normalizeDateRange(String raw) {
        String source = raw == null ? "" : raw;
        if (source.length() > MAX_DATE_RANGE_LENGTH) {
            throw new SourceValueNormalizationException(SourceValueNormalizationException.Kind.DATE_NOT_EXACT);
        }
        Matcher matcher = LEGACY_REPEATED_SEPARATOR.matcher(source);
        if (!matcher.find()) return canonicalizeOpenEnded(SourceValueNormalizerV3.normalizeDateRange(raw));

        String compatible = source.substring(0, matcher.start()) + "—" + source.substring(matcher.end());
        if (matcher.find()) {
            throw new SourceValueNormalizationException(SourceValueNormalizationException.Kind.DATE_NOT_EXACT);
        }
        return canonicalizeOpenEnded(SourceValueNormalizerV3.normalizeDateRange(compatible));
    }

    private static SourceValueNormalizerV3.NormalizedDateRange canonicalizeOpenEnded(
        SourceValueNormalizerV3.NormalizedDateRange range
    ) {
        if (!"至今".equals(range.end().text())) return range;
        return new SourceValueNormalizerV3.NormalizedDateRange(
            range.start(), range.end(), range.start().text() + "—至今");
    }
}
