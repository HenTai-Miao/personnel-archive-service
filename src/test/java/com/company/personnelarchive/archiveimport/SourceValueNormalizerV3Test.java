package com.company.personnelarchive.archiveimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceValueNormalizerV3Test {
    @ParameterizedTest
    @MethodSource("exactAges")
    void canonicalizesExactAges(String input, int value) {
        assertThat(SourceValueNormalizerV3.normalizeAge(input))
            .isEqualTo(new SourceAge(Integer.toString(value), value));
    }

    private static Stream<Arguments> exactAges() {
        return Stream.of(
            Arguments.of("三十五岁", 35),
            Arguments.of("35岁", 35),
            Arguments.of("三十五", 35),
            Arguments.of("35", 35),
            Arguments.of("三十五周岁", 35)
        );
    }

    @Test
    void keepsBlankAgeBlankAndRejectsAmbiguousAge() {
        assertThat(SourceValueNormalizerV3.normalizeAge("  "))
            .isEqualTo(new SourceAge("", null));

        assertThatThrownBy(() -> SourceValueNormalizerV3.normalizeAge("三十多岁"))
            .isInstanceOfSatisfying(SourceValueNormalizationException.class,
                exception -> assertThat(exception.kind())
                    .isEqualTo(SourceValueNormalizationException.Kind.AGE_NOT_EXACT));
        assertThatThrownBy(() -> SourceValueNormalizerV3.normalizeAge("约35岁"))
            .isInstanceOf(SourceValueNormalizationException.class);
    }

    @ParameterizedTest
    @MethodSource("canonicalDates")
    void canonicalizesDatesWithoutInventingPrecision(
        String input, String text, Year year, YearMonth month, LocalDate date
    ) {
        SourceDate normalized = SourceValueNormalizerV3.normalizeDate(input);

        assertThat(normalized.text()).isEqualTo(text);
        assertThat(normalized.year()).isEqualTo(year);
        assertThat(normalized.yearMonth()).isEqualTo(month);
        assertThat(normalized.exactDate()).isEqualTo(date);
    }

    private static Stream<Arguments> canonicalDates() {
        return Stream.of(
            Arguments.of("二〇二四年", "2024", Year.of(2024), null, null),
            Arguments.of("二〇二四年五月", "2024-05", Year.of(2024), YearMonth.of(2024, 5), null),
            Arguments.of("二〇二四年五月三日", "2024-05-03", Year.of(2024),
                YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
            Arguments.of("二〇二四.五.三", "2024-05-03", Year.of(2024),
                YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
            Arguments.of("2024.05.03", "2024-05-03", Year.of(2024),
                YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
            Arguments.of("二〇二四/五月", "2024-05", Year.of(2024), YearMonth.of(2024, 5), null),
            Arguments.of("二〇二四． 五． 三", "2024-05-03", Year.of(2024),
                YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
            Arguments.of("二〇二四年二月二十九日", "2024-02-29", Year.of(2024),
                YearMonth.of(2024, 2), LocalDate.of(2024, 2, 29))
        );
    }

    @Test
    void keepsBlankDateBlankAndRejectsAmbiguousOrInvalidDates() {
        assertThat(SourceValueNormalizerV3.normalizeDate(" \u3000 ")).isEqualTo(SourceDate.empty());

        for (String invalid : new String[]{"二〇二四年春", "大约2024年", "近期", "2023-02-29"}) {
            assertThatThrownBy(() -> SourceValueNormalizerV3.normalizeDate(invalid))
                .isInstanceOfSatisfying(SourceValueNormalizationException.class,
                    exception -> assertThat(exception.kind())
                        .isEqualTo(SourceValueNormalizationException.Kind.DATE_NOT_EXACT));
        }
    }

    @Test
    void canonicalizesClosedAndOpenDateRanges() {
        var closed = SourceValueNormalizerV3.normalizeDateRange("二〇二四． 五 － 二〇二五． 六");
        assertThat(closed.start().text()).isEqualTo("2024-05");
        assertThat(closed.end().text()).isEqualTo("2025-06");
        assertThat(closed.text()).isEqualTo("2024-05—2025-06");

        for (String source : new String[]{
            "二〇二四年五月-今", "二〇二四年五月-现在", "二〇二四年五月至今"
        }) {
            var open = SourceValueNormalizerV3.normalizeDateRange(source);
            assertThat(open.start().text()).isEqualTo("2024-05");
            assertThat(open.end().text()).isEqualTo("至今");
            assertThat(open.text()).isEqualTo("2024-05至今");
        }
    }

    @Test
    void handlesBlankAndSingleDateRangesAndRejectsAmbiguousRanges() {
        assertThat(SourceValueNormalizerV3.normalizeDateRange(" "))
            .isEqualTo(SourceValueNormalizerV3.NormalizedDateRange.empty());

        var single = SourceValueNormalizerV3.normalizeDateRange("二〇二四年五月");
        assertThat(single.start().text()).isEqualTo("2024-05");
        assertThat(single.end()).isEqualTo(SourceDate.empty());
        assertThat(single.text()).isEqualTo("2024-05");

        assertThatThrownBy(() -> SourceValueNormalizerV3.normalizeDateRange("约二〇二四年至今"))
            .isInstanceOfSatisfying(SourceValueNormalizationException.class,
                exception -> assertThat(exception.kind())
                    .isEqualTo(SourceValueNormalizationException.Kind.DATE_NOT_EXACT));
    }
}
