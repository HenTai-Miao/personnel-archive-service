/* 档案软删除事务测试：锁定原因清洗、并发保护与成功审计边界。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersonDeletionServiceTest {
    private final PersonMapper persons = mock(PersonMapper.class);
    private final AuditService audit = mock(AuditService.class);
    private PersonDeletionService service;

    @BeforeEach
    void setUp() {
        service = new PersonDeletionService(persons, audit, new ArchiveProperties(1, null, null, null));
    }

    @Test
    void softDeletesTheActiveArchiveBeforeRecordingSuccess() {
        when(persons.softDeleteActive(1, 7, 9, "误导入")).thenReturn(1);

        service.delete(7, "  误导入\n", 9);

        InOrder order = inOrder(persons, audit);
        order.verify(persons).softDeleteActive(1, 7, 9, "误导入");
        order.verify(audit).record(9L, "DELETE", "PERSON", 7L, "DELETED", true);
    }

    @Test
    void rejectsBlankReasonsWithoutWritingAnything() {
        assertThatThrownBy(() -> service.delete(7, " \n\t", 9))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                assertThat(exception.getMessage()).isEqualTo("删除原因须为 1 至 10000 个字符");
            });

        verifyNoInteractions(persons, audit);
    }

    @Test
    void rejectsReasonsLongerThanTenThousandCharacters() {
        assertThatThrownBy(() -> service.delete(7, "原".repeat(10_001), 9))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(persons, audit);
    }

    @Test
    void reportsMissingWhenTheArchiveWasAlreadyDeleted() {
        when(persons.softDeleteActive(1, 7, 9, "误导入")).thenReturn(0);

        assertThatThrownBy(() -> service.delete(7, "误导入", 9))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.code()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(exception.getMessage()).isEqualTo("档案不存在或已删除");
            });

        verifyNoInteractions(audit);
    }
}
