/* 姓名规范化：统一 Unicode 形式与空白，使重复判断和模糊搜索口径一致。 */
package com.company.personnelarchive.person;

import java.text.Normalizer;

public final class NameNormalizer {
    private NameNormalizer() {}

    public static String normalize(String value) {
        if (value == null) return null;
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .strip()
            .replaceAll("\\s+", "");
    }
}
