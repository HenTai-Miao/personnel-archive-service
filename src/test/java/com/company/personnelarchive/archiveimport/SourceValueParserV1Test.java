package com.company.personnelarchive.archiveimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceValueParserV1Test {

    @ParameterizedTest
    @MethodSource("exactAges")
    void parsesOnlyExactAges(String raw, int expected) {
        SourceAge age = SourceValueParserV1.parseAge(raw);

        assertThat(age.text()).isEqualTo(raw);
        assertThat(age.value()).isEqualTo(expected);
    }

    static Stream<Arguments> exactAges() {
        return Stream.of(
            Arguments.of("三十五岁", 35),
            Arguments.of("叁拾伍岁", 35),
            Arguments.of("35岁", 35),
            Arguments.of("三十五周岁", 35),
            Arguments.of("零岁", 0),
            Arguments.of("32767", 32_767)
        );
    }

    @ParameterizedTest
    @MethodSource("indeterminateAges")
    void keepsIndeterminateOrOutOfRangeAgeWithoutInventingValue(String raw) {
        SourceAge age = SourceValueParserV1.parseAge(raw);

        assertThat(age.text()).isEqualTo(raw);
        assertThat(age.value()).isNull();
    }

    static Stream<String> indeterminateAges() {
        return Stream.of("三十多岁", "约三十岁", "三十五岁半", "30-35岁", "-1岁", "32768岁", "");
    }

    @ParameterizedTest
    @MethodSource("dates")
    void parsesDateWithoutChangingSourcePrecision(String raw, Year year, YearMonth month, LocalDate exact) {
        SourceDate value = SourceValueParserV1.parseDate(raw);

        assertThat(value.text()).isEqualTo(raw);
        assertThat(value.year()).isEqualTo(year);
        assertThat(value.yearMonth()).isEqualTo(month);
        assertThat(value.exactDate()).isEqualTo(exact);
    }

    static Stream<Arguments> dates() {
        return Stream.of(
            Arguments.of("二〇二四年", Year.of(2024), null, null),
            Arguments.of("二〇2四年", Year.of(2024), null, null),
            Arguments.of("二千零二十四年", Year.of(2024), null, null),
            Arguments.of("贰仟零贰拾肆年", Year.of(2024), null, null),
            Arguments.of("二〇二四年五月", Year.of(2024), YearMonth.of(2024, 5), null),
            Arguments.of("二〇二四年五月三日", Year.of(2024), YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
            Arguments.of("2024", Year.of(2024), null, null),
            Arguments.of("2024.05", Year.of(2024), YearMonth.of(2024, 5), null),
            Arguments.of("2024/5/3", Year.of(2024), YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
            Arguments.of("2024-05-03", Year.of(2024), YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3))
        );
    }

    @Test
    void validatesRealCalendarDates() {
        assertThat(SourceValueParserV1.parseDate("二〇二四年二月二十九日").exactDate())
            .isEqualTo(LocalDate.of(2024, 2, 29));

        assertThatThrownBy(() -> SourceValueParserV1.parseDate("二〇二三年二月二十九日"))
            .isInstanceOf(DateTimeException.class);
        assertThatThrownBy(() -> SourceValueParserV1.parseDate("二〇二四年十三月"))
            .isInstanceOf(DateTimeException.class);
        assertThatThrownBy(() -> SourceValueParserV1.parseDate("大约二〇二四年"))
            .isInstanceOf(DateTimeException.class);
    }

    @Test
    void parsesChineseRangesAndPresentMarkerWithoutInventingAnEndDate() {
        SourceValueParserV1.DateRange closed =
            SourceValueParserV1.parseDateRange("二〇二四年五月—二〇二五年六月三日");
        SourceValueParserV1.DateRange open = SourceValueParserV1.parseDateRange("二〇二四年五月至今");

        assertThat(closed.start().text()).isEqualTo("二〇二四年五月");
        assertThat(closed.start().yearMonth()).isEqualTo(YearMonth.of(2024, 5));
        assertThat(closed.end().text()).isEqualTo("二〇二五年六月三日");
        assertThat(closed.end().exactDate()).isEqualTo(LocalDate.of(2025, 6, 3));
        assertThat(open.start().yearMonth()).isEqualTo(YearMonth.of(2024, 5));
        assertThat(open.end().text()).isEqualTo("至今");
        assertThat(open.end().year()).isNull();
    }

    @Test
    void returnsEmptyRangeWhenTextIsNotAnExactDateRange() {
        assertThat(SourceValueParserV1.parseDateRange("约二〇二四年至今"))
            .isEqualTo(SourceValueParserV1.DateRange.empty());
    }
}
