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

class SourceValueParserV2Test {

    @ParameterizedTest
    @MethodSource("mixedDelimitedDates")
    void parsesMixedChineseDelimitedDatesWithoutChangingSourceText(
        String raw, Year year, YearMonth month, LocalDate exact) {
        SourceDate parsed = SourceValueParserV2.parseDate(raw);

        assertThat(parsed.text()).isEqualTo(raw);
        assertThat(parsed.year()).isEqualTo(year);
        assertThat(parsed.yearMonth()).isEqualTo(month);
        assertThat(parsed.exactDate()).isEqualTo(exact);
    }

    static Stream<Arguments> mixedDelimitedDates() {
        return Stream.of(
            Arguments.of("二〇二四.五", Year.of(2024), YearMonth.of(2024, 5), null),
            Arguments.of("二〇二四.五.三", Year.of(2024), YearMonth.of(2024, 5),
                LocalDate.of(2024, 5, 3)),
            Arguments.of("二〇二四/5/三", Year.of(2024), YearMonth.of(2024, 5),
                LocalDate.of(2024, 5, 3)),
            Arguments.of("2024-五月", Year.of(2024), YearMonth.of(2024, 5), null),
            Arguments.of("二〇2四年.05月.三日", Year.of(2024), YearMonth.of(2024, 5),
                LocalDate.of(2024, 5, 3)),
            Arguments.of("二〇二四/五月-3日", Year.of(2024), YearMonth.of(2024, 5),
                LocalDate.of(2024, 5, 3)),
            Arguments.of("二〇二四．五．三", Year.of(2024), YearMonth.of(2024, 5),
                LocalDate.of(2024, 5, 3))
        );
    }

    @Test
    void keepsV1FrozenWhileV2AddsTheNewFormat() {
        assertThatThrownBy(() -> SourceValueParserV1.parseDate("二〇二四.五.三"))
            .isInstanceOf(DateTimeException.class);

        assertThat(SourceValueParserV2.parseDate("二〇二四.五.三").exactDate())
            .isEqualTo(LocalDate.of(2024, 5, 3));
        assertThat(SourceValueParserV2.parseDate("二〇二四年五月三日"))
            .isEqualTo(SourceValueParserV1.parseDate("二〇二四年五月三日"));
    }

    @ParameterizedTest
    @MethodSource("invalidDates")
    void rejectsInvalidCalendarOrUnsupportedDelimitedDates(String raw) {
        assertThatThrownBy(() -> SourceValueParserV2.parseDate(raw))
            .isInstanceOf(DateTimeException.class);
    }

    static Stream<String> invalidDates() {
        return Stream.of(
            "二〇二三.二.二十九",
            "二〇二四.十三.三",
            "二四.五.三",
            "二〇二四.五.三号",
            "二〇二四.日",
            "二〇二四.五.",
            "二〇二四.十5.三"
        );
    }

    @Test
    void delegatesAgeSemanticsToV1() {
        assertThat(SourceValueParserV2.parseAge("三十五岁"))
            .isEqualTo(new SourceAge("三十五岁", 35));
        assertThat(SourceValueParserV2.parseAge("三十多岁"))
            .isEqualTo(new SourceAge("三十多岁", null));
    }

    @Test
    void parsesHyphenatedScalarBeforeLookingForRangeSeparators() {
        SourceValueParserV2.DateRange parsed =
            SourceValueParserV2.parseDateRange("二〇二四-五-三");

        assertThat(parsed.start().text()).isEqualTo("二〇二四-五-三");
        assertThat(parsed.start().exactDate()).isEqualTo(LocalDate.of(2024, 5, 3));
        assertThat(parsed.end()).isEqualTo(SourceDate.empty());
    }

    @Test
    void parsesMixedDelimitedClosedAndOpenRanges() {
        SourceValueParserV2.DateRange closed =
            SourceValueParserV2.parseDateRange("二〇二四.五-二〇二五.六");
        SourceValueParserV2.DateRange open =
            SourceValueParserV2.parseDateRange("二〇二四.五至今");

        assertThat(closed.start().text()).isEqualTo("二〇二四.五");
        assertThat(closed.start().yearMonth()).isEqualTo(YearMonth.of(2024, 5));
        assertThat(closed.end().text()).isEqualTo("二〇二五.六");
        assertThat(closed.end().yearMonth()).isEqualTo(YearMonth.of(2025, 6));
        assertThat(open.start().yearMonth()).isEqualTo(YearMonth.of(2024, 5));
        assertThat(open.end().text()).isEqualTo("至今");
        assertThat(open.end().year()).isNull();
    }

    @Test
    void preservesFullwidthPunctuationAndInternalWhitespaceInRangeEndpoints() {
        SourceValueParserV2.DateRange parsed =
            SourceValueParserV2.parseDateRange("二〇二四． 五 － 二〇二五． 六");

        assertThat(parsed.start().text()).isEqualTo("二〇二四． 五");
        assertThat(parsed.start().yearMonth()).isEqualTo(YearMonth.of(2024, 5));
        assertThat(parsed.end().text()).isEqualTo("二〇二五． 六");
        assertThat(parsed.end().yearMonth()).isEqualTo(YearMonth.of(2025, 6));
    }

    @Test
    void preservesV1CompatibleRangeEndpointSourceText() {
        SourceValueParserV2.DateRange parsed =
            SourceValueParserV2.parseDateRange("2024． 05 － 2025． 06");

        assertThat(parsed.start().text()).isEqualTo("2024． 05");
        assertThat(parsed.start().yearMonth()).isEqualTo(YearMonth.of(2024, 5));
        assertThat(parsed.end().text()).isEqualTo("2025． 06");
        assertThat(parsed.end().yearMonth()).isEqualTo(YearMonth.of(2025, 6));
    }

    @Test
    void returnsEmptyRangeForUnsupportedText() {
        assertThat(SourceValueParserV2.parseDateRange("约二〇二四.五至今"))
            .isEqualTo(SourceValueParserV2.DateRange.empty());
    }
}
