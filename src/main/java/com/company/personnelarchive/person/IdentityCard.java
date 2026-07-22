/* 身份证值对象规则：规范化输入、验证日期与校验位，并提供安全末四位。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;

import java.time.DateTimeException;
import java.time.LocalDate;

public final class IdentityCard {
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECKS = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdentityCard() {}

    public static String normalizeAndValidate(String value) {
        if (value == null) throw new BusinessException(ErrorCode.INVALID_IDENTITY_CARD);
        String normalized = value.replaceAll("\\s+", "").toUpperCase();
        if (!normalized.matches("\\d{17}[0-9X]")) {
            throw new BusinessException(ErrorCode.INVALID_IDENTITY_CARD);
        }
        try {
            LocalDate.parse(normalized.substring(6, 14), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_IDENTITY_CARD);
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (normalized.charAt(i) - '0') * WEIGHTS[i];
        if (CHECKS[sum % 11] != normalized.charAt(17)) {
            throw new BusinessException(ErrorCode.INVALID_IDENTITY_CARD);
        }
        return normalized;
    }

    public static String lastFour(String normalized) {
        return normalized.substring(normalized.length() - 4);
    }
}
