/* 批量导出单元测试：锁定 ZIP 条目命名、顺序、审计和整批失败语义。 */
package com.company.personnelarchive.archiveexport;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchArchiveExportServiceTest {
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-22T03:04:05Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void packagesEveryWorkbookWithoutOverwritingDuplicateNames() throws Exception {
        // Given
        ArchiveExportService workbookExports = mock(ArchiveExportService.class);
        AuditService audit = mock(AuditService.class);
        when(workbookExports.generateForBatch(7)).thenReturn(
            new ArchiveExportService.GeneratedArchive("人员档案-测试甲.xlsx", new byte[]{1, 2}));
        when(workbookExports.generateForBatch(8)).thenReturn(
            new ArchiveExportService.GeneratedArchive("人员档案-测试甲.xlsx", new byte[]{3, 4}));
        var service = new BatchArchiveExportService(workbookExports, audit, CLOCK);

        // When
        var bundle = service.generate(List.of("7", "8"), 9);

        // Then
        assertThat(bundle.filename()).isEqualTo("人员档案批量导出-20260722-110405.zip");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bundle.bytes()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("人员档案-测试甲.xlsx");
            assertThat(zip.readAllBytes()).containsExactly(1, 2);
            assertThat(zip.getNextEntry().getName()).isEqualTo("人员档案-测试甲 (1).xlsx");
            assertThat(zip.readAllBytes()).containsExactly(3, 4);
            assertThat(zip.getNextEntry()).isNull();
        }
        verify(audit).record(9L, "EXPORT", "PERSON", 7L, "SUCCESS", true);
        verify(audit).record(9L, "EXPORT", "PERSON", 8L, "SUCCESS", true);
    }

    @Test
    void recordsNoSuccessfulExportsWhenAnyWorkbookCannotBeGenerated() {
        // Given
        ArchiveExportService workbookExports = mock(ArchiveExportService.class);
        AuditService audit = mock(AuditService.class);
        when(workbookExports.generateForBatch(7)).thenReturn(
            new ArchiveExportService.GeneratedArchive("人员档案-测试甲.xlsx", new byte[]{1}));
        when(workbookExports.generateForBatch(8)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        var service = new BatchArchiveExportService(workbookExports, audit, CLOCK);

        // When / Then
        assertThatThrownBy(() -> service.generate(List.of("7", "8"), 9))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsDuplicateIdentifiersBeforeGeneratingWorkbooks() {
        // Given
        ArchiveExportService workbookExports = mock(ArchiveExportService.class);
        AuditService audit = mock(AuditService.class);
        var service = new BatchArchiveExportService(workbookExports, audit, CLOCK);

        // When / Then
        assertThatThrownBy(() -> service.generate(List.of("7", "7"), 9))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(workbookExports, audit);
    }
}
