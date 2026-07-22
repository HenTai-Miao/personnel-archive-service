/* 周岁计算器：以出生日期和注入时钟计算展示年龄，不把年龄写回数据库。 */
package com.company.personnelarchive.family;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;

public final class AgeCalculator {
    private AgeCalculator() {}

    public static int completedYears(LocalDate birthDate, Clock clock) {
        if (birthDate == null) throw new IllegalArgumentException("birthDate is required");
        LocalDate today = LocalDate.now(clock);
        if (birthDate.isAfter(today)) throw new IllegalArgumentException("birthDate cannot be in the future");
        return Period.between(birthDate, today).getYears();
    }
}
