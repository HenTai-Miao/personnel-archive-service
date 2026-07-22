/* 姓名规范化测试：锁定 Unicode NFKC、空白处理和重复匹配口径。 */
package com.company.personnelarchive.person;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameNormalizerTest {
    @Test
    void appliesNfkcAndRemovesWhitespace() {
        assertThat(NameNormalizer.normalize("  测 试Ａ  ")).isEqualTo("测试A");
    }
}
