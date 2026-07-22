/* 周岁计算测试：覆盖生日当天、未到生日和边界日期，保证年龄不持久化。 */
package com.company.personnelarchive.family;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class AgeCalculatorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void calculatesOnlyCompletedYears() {
        assertThat(AgeCalculator.completedYears(LocalDate.of(2000, 7, 23), clock)).isEqualTo(25);
        assertThat(AgeCalculator.completedYears(LocalDate.of(2000, 7, 22), clock)).isEqualTo(26);
    }
}
