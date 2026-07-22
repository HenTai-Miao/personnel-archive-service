/* 敏感值脱敏测试：前四后三可见，异常短值不得因区间重叠而完整泄露。 */
package com.company.personnelarchive.person;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveValueMaskerTest {
    @Test
    void keepsFirstFourAndLastThreeWhenValueIsLongEnough() {
        // Given / When
        String identity = SensitiveValueMasker.mask("123456789012345678");
        String phone = SensitiveValueMasker.mask("13800138000");

        // Then
        assertThat(identity).isEqualTo("1234***********678");
        assertThat(phone).isEqualTo("1380****000");
    }

    @Test
    void hidesEveryCharacterWhenValueIsTooShortToMaskSafely() {
        // Given / When
        String masked = SensitiveValueMasker.mask("1234567");

        // Then
        assertThat(masked).isEqualTo("*******");
    }

    @Test
    void preservesBlankOptionalValues() {
        // Given / When / Then
        assertThat(SensitiveValueMasker.mask(null)).isEmpty();
        assertThat(SensitiveValueMasker.mask("")).isEmpty();
    }
}
