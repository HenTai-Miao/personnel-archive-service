package com.company.personnelarchive.archiveimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceValueNormalizerV4Test {
    @Test
    void rejectsDateRangeLongerThanCurrentBoundaryBeforeNormalization() {
        // Given: a cell exceeds the 128-character date-range boundary but would become valid after strip().
        String input = " ".repeat(129) + "2018-06至今";

        // When / Then
        assertThatThrownBy(() -> SourceValueNormalizerV4.normalizeDateRange(input))
            .isInstanceOfSatisfying(SourceValueNormalizationException.class,
                exception -> assertThat(exception.kind())
                    .isEqualTo(SourceValueNormalizationException.Kind.DATE_NOT_EXACT));
    }

    @Test
    void acceptsDateRangeAtCurrentLengthBoundary() {
        // Given: the raw cell text is exactly at the documented 128-character boundary.
        String range = "2018-06至今";
        String input = " ".repeat(128 - range.length()) + range;

        // When / Then
        assertThat(SourceValueNormalizerV4.normalizeDateRange(input).text())
            .isEqualTo("2018-06—至今");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2018-06至今",
        "2018-06-今",
        "2018-06—至今",
        "二〇一八年六月至今"
    })
    void canonicalizesOpenEndedRangesWithVisibleSeparator(String input) {
        // Given: an exact open-ended date range uses one of the already supported source forms.

        // When
        var normalized = SourceValueNormalizerV4.normalizeDateRange(input);

        // Then
        assertThat(normalized.start().text()).isEqualTo("2018-06");
        assertThat(normalized.end().text()).isEqualTo("至今");
        assertThat(normalized.text()).isEqualTo("2018-06—至今");
    }

    @ParameterizedTest
    @MethodSource("legacyRepeatedSeparatorRanges")
    void canonicalizesLegacyRepeatedDateRangeSeparators(String input, String expected) {
        // Given: a historical workbook uses one supported legacy separator between exact endpoints.

        // When
        var normalized = SourceValueNormalizerV4.normalizeDateRange(input);

        // Then
        assertThat(normalized.text()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1996.09----1999.06",
        "1996.09———1999.06",
        "1996.09--—1999.06",
        "1996.09--约1999.06",
        "1996.09--1999.06--2000.01"
    })
    void rejectsMalformedOrAmbiguousRepeatedSeparatorRanges(String input) {
        // Given: a value is not one exact two-endpoint range.

        // When / Then
        assertThatThrownBy(() -> SourceValueNormalizerV4.normalizeDateRange(input))
            .isInstanceOfSatisfying(SourceValueNormalizationException.class,
                exception -> assertThat(exception.kind())
                    .isEqualTo(SourceValueNormalizationException.Kind.DATE_NOT_EXACT));
    }

    private static Stream<Arguments> legacyRepeatedSeparatorRanges() {
        return Stream.of(
            Arguments.of("1996.09--1999.06", "1996-09—1999-06"),
            Arguments.of("1999.09---2002.06", "1999-09—2002-06"),
            Arguments.of("2003.04～2009.06", "2003-04—2009-06"),
            Arguments.of("2009.10——2018.11", "2009-10—2018-11")
        );
    }
}
