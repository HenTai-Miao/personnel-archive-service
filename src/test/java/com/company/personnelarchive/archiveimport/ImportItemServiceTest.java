/* 单文件导入事务测试：覆盖新档案、冲突、待决定、跳过、覆盖和陈旧决定。 */
package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.person.PersonMapper;
import com.company.personnelarchive.person.SnapshotWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ImportItemServiceTest {
    private final ImportMapper imports = mock(ImportMapper.class);
    private final PersonMapper persons = mock(PersonMapper.class);
    private final SnapshotWriter writer = mock(SnapshotWriter.class);
    private final ExcelArchiveReader reader = mock(ExcelArchiveReader.class);
    private final AuditService audit = mock(AuditService.class);
    private final PendingImportStore pending = mock(PendingImportStore.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    private final ArchiveProperties properties = new ArchiveProperties(1,
        new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000, "x".repeat(32), "h".repeat(32),
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
        new ArchiveProperties.Excel(12_582_912, 5_242_880, 500, "classpath:/excel/personnel-archive-template-v1.xlsx"),
        new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
    private ImportItemService service;
    private ArchiveDocument document;
    private SnapshotWriter.PreparedArchive prepared;

    @BeforeEach
    void setUp() {
        service = new ImportItemService(imports, persons, writer, reader, properties, audit, clock, pending);
        document = new ArchiveDocument("T-001", "测试甲", "990000200001010012", null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            List.of(), List.of(), List.of(), List.of(), null, "测试甲", LocalDate.of(2026, 7, 22));
        prepared = new SnapshotWriter.PreparedArchive(document, "T-001", "测试甲", "测试甲",
            "990000200001010012", new byte[]{1, 2, 3});
    }

    @Test
    void stripsClientPathsBeforePersistingAndReturningTheFilename() {
        // Given
        SnapshotWriter.PreparedArchive blank = new SnapshotWriter.PreparedArchive(
            document, "", "", "", null, null);
        when(reader.read(any(), anyLong(), any())).thenReturn(document);
        when(writer.prepare(document)).thenReturn(blank);
        when(writer.insert(blank, 9, 1, null)).thenReturn(21L);
        doAnswer(invocation -> {
            ((ImportMapper.ItemInsert) invocation.getArgument(0)).id = 12L;
            return 1;
        }).when(imports).insertItem(any());

        // When
        ImportDtos.ItemResult result = service.precheck(
            5, 1, "C:\\Users\\tester\\档案甲.xlsx", new byte[]{1, 2}, 9);

        // Then
        assertThat(result.fileName()).isEqualTo("档案甲.xlsx");
        verify(reader).read(any(), anyLong(), eq("档案甲.xlsx"));
        var inserted = org.mockito.ArgumentCaptor.forClass(ImportMapper.ItemInsert.class);
        verify(imports).insertItem(inserted.capture());
        assertThat(inserted.getValue().filename).isEqualTo("档案甲.xlsx");
    }

    @Test
    void replacesABlankFilenameBeforePersistingAndReturningTheFailure() {
        // Given
        doAnswer(invocation -> {
            ((ImportMapper.ItemInsert) invocation.getArgument(0)).id = 15L;
            return 1;
        }).when(imports).insertItem(any());

        // When
        ImportDtos.ItemResult result = service.recordFailure(
            5, 1, "  ", new byte[]{1, 2}, ErrorCode.TEMPLATE_INVALID, "模板错误", 9);

        // Then
        assertThat(result.fileName()).isEqualTo("unnamed.xlsx");
        var inserted = org.mockito.ArgumentCaptor.forClass(ImportMapper.ItemInsert.class);
        verify(imports).insertItem(inserted.capture());
        assertThat(inserted.getValue().filename).isEqualTo("unnamed.xlsx");
    }

    @Test
    void duplicatePrecheckWritesOnlyPendingMetadata() {
        PersonMapper.ActivePerson active = new PersonMapper.ActivePerson(7, "T-000", "测试甲", "测试甲",
            new byte[]{1, 2, 3}, "0012", 3, Instant.parse("2026-07-21T00:00:00Z"));
        when(reader.read(any(), anyLong(), any())).thenReturn(document);
        when(writer.prepare(document)).thenReturn(prepared);
        when(persons.findActiveByIdentity(1, prepared.identityHmac())).thenReturn(active);
        when(persons.findActiveByEmployeeNo(1, "T-001")).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((ImportMapper.ItemInsert) invocation.getArgument(0)).id = 11L;
            return 1;
        }).when(imports).insertItem(any());

        ImportDtos.ItemResult result = service.precheck(5, 1, "fixture.xlsx", new byte[]{1, 2}, 9);

        assertThat(result.status()).isEqualTo("PENDING_DECISION");
        assertThat(result.duplicate().incomingEmployeeNo()).isEqualTo("T-001");
        verify(writer, never()).insert(any(), anyLong(), anyLong(), any());
        verify(pending).put(11, document, 7, 3, Instant.parse("2026-07-22T00:30:00Z"));
    }

    @Test
    void blankDuplicateKeysImportWithoutConflictLookup() {
        // Given
        SnapshotWriter.PreparedArchive blank = new SnapshotWriter.PreparedArchive(
            document, "", "", "", null, null);
        when(reader.read(any(), anyLong(), any())).thenReturn(document);
        when(writer.prepare(document)).thenReturn(blank);
        when(writer.insert(blank, 9, 1, null)).thenReturn(21L);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((ImportMapper.ItemInsert) invocation.getArgument(0)).id = 12L;
            return 1;
        }).when(imports).insertItem(any());

        // When
        ImportDtos.ItemResult result = service.precheck(5, 1, "blank.xlsx", new byte[]{1, 2}, 9);

        // Then
        assertThat(result.status()).isEqualTo("IMPORTED");
        verifyNoInteractions(persons);
    }

    @Test
    void duplicateWithBlankEmployeeNumberStillWaitsForUserDecision() {
        // Given
        SnapshotWriter.PreparedArchive withoutEmployeeNo = new SnapshotWriter.PreparedArchive(
            document, "", "测试甲", "测试甲", "990000200001010012", new byte[]{1, 2, 3});
        PersonMapper.ActivePerson active = new PersonMapper.ActivePerson(7, "", "测试甲", "测试甲",
            new byte[]{1, 2, 3}, "0012", 3, Instant.parse("2026-07-21T00:00:00Z"));
        when(reader.read(any(), anyLong(), any())).thenReturn(document);
        when(writer.prepare(document)).thenReturn(withoutEmployeeNo);
        when(persons.findActiveByIdentity(1, withoutEmployeeNo.identityHmac())).thenReturn(active);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((ImportMapper.ItemInsert) invocation.getArgument(0)).id = 13L;
            return 1;
        }).when(imports).insertItem(any());

        // When
        ImportDtos.ItemResult result = service.precheck(5, 1, "duplicate.xlsx", new byte[]{1, 2}, 9);

        // Then
        assertThat(result.status()).isEqualTo("PENDING_DECISION");
        verify(persons).findActiveByIdentity(1, withoutEmployeeNo.identityHmac());
        verifyNoMoreInteractions(persons);
    }

    @Test
    void sameNameWithoutIdentityImportsWithoutDuplicateDecision() {
        // Given
        SnapshotWriter.PreparedArchive withoutIdentity = new SnapshotWriter.PreparedArchive(
            document, "", "测试甲", "测试甲", null, null);
        when(reader.read(any(), anyLong(), any())).thenReturn(document);
        when(writer.prepare(document)).thenReturn(withoutIdentity);
        when(writer.insert(withoutIdentity, 9, 1, null)).thenReturn(22L);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((ImportMapper.ItemInsert) invocation.getArgument(0)).id = 14L;
            return 1;
        }).when(imports).insertItem(any());

        // When
        ImportDtos.ItemResult result = service.precheck(5, 1, "name-only.xlsx", new byte[]{1, 2}, 9);

        // Then
        assertThat(result.status()).isEqualTo("IMPORTED");
        verifyNoInteractions(persons);
        verify(pending, never()).put(anyLong(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void sameIdentityWithDifferentNameIsAConflictInsteadOfADuplicateDecision() {
        // Given
        SnapshotWriter.PreparedArchive differentName = new SnapshotWriter.PreparedArchive(
            document, "", "测试乙", "测试乙", "990000200001010012", new byte[]{1, 2, 3});
        PersonMapper.ActivePerson active = new PersonMapper.ActivePerson(7, "", "测试甲", "测试甲",
            new byte[]{1, 2, 3}, "0012", 3, Instant.parse("2026-07-21T00:00:00Z"));
        when(reader.read(any(), anyLong(), any())).thenReturn(document);
        when(writer.prepare(document)).thenReturn(differentName);
        when(persons.findActiveByIdentity(1, differentName.identityHmac())).thenReturn(active);

        // When / Then
        assertThatThrownBy(() -> service.precheck(5, 1, "identity-conflict.xlsx", new byte[]{1, 2}, 9))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.IDENTITY_CARD_CONFLICT));
        verify(pending, never()).put(anyLong(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void overwriteSoftDeletesThenCreatesAndLinksNewSnapshot() {
        Instant expires = Instant.parse("2026-07-22T00:30:00Z");
        ImportMapper.DecisionRow item = new ImportMapper.DecisionRow(11, 1, 5, "fixture.xlsx", 2,
            new byte[]{9}, "PENDING_DECISION", 7L, 3L, expires);
        PersonMapper.ActivePerson active = new PersonMapper.ActivePerson(7, "T-001", "测试甲", "测试甲",
            prepared.identityHmac(), "0012", 3, Instant.parse("2026-07-21T00:00:00Z"));
        when(imports.lockDecision(1, 11)).thenReturn(item);
        when(pending.get(11)).thenReturn(Optional.of(new PendingImportStore.Entry(document, 7, 3, expires)));
        when(writer.prepare(document)).thenReturn(prepared);
        when(persons.lockActiveById(1, 7)).thenReturn(active);
        when(persons.findActiveByEmployeeNo(1, "T-001")).thenReturn(active);
        when(persons.softDeleteForOverwrite(7, 3, 9)).thenReturn(1);
        when(persons.linkReplacement(7, 22, 9)).thenReturn(1);
        when(writer.insert(prepared, 9, 4, 7L)).thenReturn(22L);

        ImportDtos.ItemResult result = service.decide(11, "OVERWRITE", 9);

        assertThat(result.status()).isEqualTo("OVERWRITTEN");
        var order = inOrder(persons, writer);
        order.verify(persons).softDeleteForOverwrite(7, 3, 9);
        order.verify(writer).insert(prepared, 9, 4, 7L);
        order.verify(persons).linkReplacement(7, 22, 9);
        verify(imports).completeDecision(11, "OVERWRITTEN", "OVERWRITTEN", "已创建新快照并保留历史版本", 22L, "OVERWRITE", 9);
    }
}
