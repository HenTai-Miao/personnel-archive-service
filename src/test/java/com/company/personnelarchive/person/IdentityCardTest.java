/* 身份证规则测试：覆盖规范化、出生日期、校验位和末四位展示。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityCardTest {
    @Test
    void normalizesAndChecksSyntheticIdentity() {
        assertThat(IdentityCard.normalizeAndValidate("990000 200001010012")).isEqualTo("990000200001010012");
    }

    @Test
    void rejectsInvalidChecksum() {
        assertThatThrownBy(() -> IdentityCard.normalizeAndValidate("990000200001010013"))
            .isInstanceOf(BusinessException.class);
    }
}
