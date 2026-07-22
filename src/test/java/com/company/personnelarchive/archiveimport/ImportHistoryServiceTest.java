/* 导入记录查询测试：锁定服务端分页、逐文件结果映射和查看审计。 */
package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportHistoryServiceTest {
    @Test
    void returnsPersistedFileResultsWhenReadingAHistoryPage() {
        // Given
        ImportMapper mapper = mock(ImportMapper.class);
        AuditService audit = mock(AuditService.class);
        Instant processedAt = Instant.parse("2026-07-23T01:02:03Z");
        when(mapper.countHistory(1)).thenReturn(21L);
        when(mapper.findHistory(1, 20, 20)).thenReturn(List.of(new ImportMapper.HistoryRow(
            9_007_199_254_740_993L, 9_007_199_254_740_995L, "测试管理员",
            "C:\\Users\\tester\\档案甲.xlsx",
            "FAILED", "TEMPLATE_INVALID", "档案模板结构不正确", processedAt)));
        ImportHistoryService service = new ImportHistoryService(mapper, properties(), audit);

        // When
        ImportDtos.HistoryPage result = service.history(1, 20, 9);

        // Then
        assertThat(result.content()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo(9_007_199_254_740_993L);
            assertThat(item.batchId()).isEqualTo(9_007_199_254_740_995L);
            assertThat(item.operatorName()).isEqualTo("测试管理员");
            assertThat(item.fileName()).isEqualTo("档案甲.xlsx");
            assertThat(item.status()).isEqualTo("FAILED");
            assertThat(item.code()).isEqualTo("TEMPLATE_INVALID");
            assertThat(item.message()).isEqualTo("档案模板结构不正确");
            assertThat(item.processedAt()).isEqualTo("2026-07-23T01:02:03Z");
        });
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        verify(audit).record(9L, "VIEW", "IMPORT_HISTORY", null, "SUCCESS", true);
    }

    @Test
    void rejectsAnInvalidHistoryPageBeforeQueryingTheDatabase() {
        // Given
        ImportHistoryService service = new ImportHistoryService(
            mock(ImportMapper.class), properties(), mock(AuditService.class));

        // When / Then
        assertThatThrownBy(() -> service.history(-1, 20, 9))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1, null, null, null);
    }
}
