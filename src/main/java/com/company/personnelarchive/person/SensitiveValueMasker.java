/* 身份证与手机号共用的安全展示规则；短值全部隐藏，避免可见区间重叠泄露原值。 */
package com.company.personnelarchive.person;

final class SensitiveValueMasker {
    private static final int PREFIX_LENGTH = 4;
    private static final int SUFFIX_LENGTH = 3;
    private static final int VISIBLE_LENGTH = PREFIX_LENGTH + SUFFIX_LENGTH;

    private SensitiveValueMasker() {}

    static String mask(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() <= VISIBLE_LENGTH) return "*".repeat(value.length());
        return value.substring(0, PREFIX_LENGTH)
            + "*".repeat(value.length() - VISIBLE_LENGTH)
            + value.substring(value.length() - SUFFIX_LENGTH);
    }
}
