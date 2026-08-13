package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.person.PersonMapper;
import com.company.personnelarchive.person.SnapshotWriter;
import com.company.personnelarchive.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportOperationalLoggingTest {
    private static final String FILE_NAME = "workbook-name-secret-sentinel.xlsx";
    private static final String PERSON_NAME = "person-name-secret-sentinel";
    private static final String IDENTITY_SUFFIX = "identity-suffix-secret-sentinel";
    private static final String EMPLOYEE_NO = "employee-number-secret-sentinel";
    private static final String CELL_MESSAGE = "sheet-and-cell-secret-sentinel";
    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @Test
    void logsImportedItemIdsCountsAndSizeWithoutWorkbookContent() {
        ItemFixture fixture = new ItemFixture();
        fixture.stubParsedDocument(fixture.prepared(null, null));
        when(fixture.writer.insert(any(), anyLong(), anyLong(), any())).thenReturn(21L);
        fixture.assignItemId(12L);

        try (var logs = LogCapture.attach(ImportItemService.class)) {
            fixture.service.precheck(5L, 2, FILE_NAME, new byte[]{1, 2, 3}, 9L);

            assertThat(logs.joined())
                .contains("event=import_item_started", "batchId=5", "itemOrder=2", "fileSize=3",
                    "event=import_item_parsed", "mainEducationCount=1", "workCount=1",
                    "rewardCount=1", "familyCount=1", "event=import_item_completed",
                    "itemId=12", "personId=21", "result=IMPORTED", "durationMs=");
            assertItemDataRedacted(logs.joined());
        }
    }

    @Test
    void logsPendingDuplicateWithoutItsSummaryValues() {
        ItemFixture fixture = new ItemFixture();
        byte[] identityHmac = new byte[]{1, 2, 3};
        SnapshotWriter.PreparedArchive prepared = fixture.prepared(EMPLOYEE_NO, identityHmac);
        fixture.stubParsedDocument(prepared);
        PersonMapper.ActivePerson active = new PersonMapper.ActivePerson(7L, EMPLOYEE_NO, PERSON_NAME, PERSON_NAME,
            identityHmac, IDENTITY_SUFFIX, 3L, NOW.minusSeconds(60));
        when(fixture.persons.findActiveByIdentity(1L, identityHmac)).thenReturn(active);
        fixture.assignItemId(13L);

        try (var logs = LogCapture.attach(ImportItemService.class)) {
            fixture.service.precheck(5L, 1, FILE_NAME, new byte[]{1, 2}, 9L);

            assertThat(logs.joined())
                .contains("event=import_item_completed", "itemId=13", "personId=7",
                    "result=PENDING_DECISION", "durationMs=");
            assertItemDataRedacted(logs.joined());
        }
    }

    @Test
    void logsPersistedFailureCodeButNotItsClientMessage() {
        ItemFixture fixture = new ItemFixture();
        fixture.assignItemId(14L);

        try (var logs = LogCapture.attach(ImportItemService.class)) {
            fixture.service.recordFailure(5L, 1, FILE_NAME, new byte[]{1, 2},
                ErrorCode.IMPORT_FIELD_VALIDATION_FAILED, CELL_MESSAGE, 9L);

            assertThat(logs.joined())
                .contains("event=import_item_completed", "batchId=5", "itemOrder=1", "itemId=14",
                    "result=FAILED", "code=IMPORT_FIELD_VALIDATION_FAILED", "durationMs=")
                .doesNotContain(CELL_MESSAGE);
            assertItemDataRedacted(logs.joined());
        }
    }

    @Test
    void logsOnlyAFixedCodeAndSafeSummaryForUnknownBatchFailure() {
        ImportMapper mapper = mock(ImportMapper.class);
        ImportItemService items = mock(ImportItemService.class);
        doAnswer(invocation -> {
            ((ImportMapper.BatchInsert) invocation.getArgument(0)).id = 31L;
            return 1;
        }).when(mapper).insertBatch(any());
        RuntimeException failure = new RuntimeException(
            "password-secret-sentinel /private/workbook-path-secret-sentinel 基本情况!F3");
        when(items.precheck(anyLong(), any(Integer.class), any(), any(), anyLong())).thenThrow(failure);
        when(items.recordFailure(anyLong(), any(Integer.class), any(), any(), any(), any(), anyLong()))
            .thenReturn(new ImportDtos.ItemResult(41L, FILE_NAME, "FAILED", "INTERNAL_ERROR",
                CELL_MESSAGE, null));
        when(mapper.findBatch(1L, 31L)).thenReturn(new ImportMapper.BatchRow(
            31L, "COMPLETED_WITH_ERRORS", 1, 0, 0, 0, 0, 1, NOW, NOW));
        ImportBatchService service = new ImportBatchService(mapper, items, properties());
        var file = new MockMultipartFile("files", FILE_NAME,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        try (var logs = LogCapture.attach(ImportBatchService.class)) {
            service.precheck(List.of(file), 9L);

            assertThat(logs.joined())
                .contains("event=import_batch_started", "operatorId=9", "batchId=31", "fileCount=1",
                    "event=import_item_failed", "code=INTERNAL_ERROR",
                    "exceptionType=java.lang.RuntimeException", "event=import_batch_completed",
                    "failedCount=1", "durationMs=")
                .doesNotContain("password-secret-sentinel", "workbook-path-secret-sentinel", "基本情况!F3",
                    FILE_NAME, CELL_MESSAGE);
        }
    }

    @Test
    void logsSkipDecisionWithOnlyTheEnumAndInternalIds() {
        ItemFixture fixture = new ItemFixture();
        fixture.stubDecision(11L, 7L);

        try (var logs = LogCapture.attach(ImportItemService.class)) {
            fixture.service.decide(11L, "SKIP", 9L);

            assertThat(logs.joined())
                .contains("event=import_decision_started", "itemId=11", "operatorId=9", "decision=SKIP",
                    "event=import_decision_completed", "result=SKIPPED", "personId=7", "durationMs=");
            assertItemDataRedacted(logs.joined());
        }
    }

    @Test
    void logsOverwriteDecisionWithoutArchiveValues() {
        ItemFixture fixture = new ItemFixture();
        byte[] identityHmac = new byte[]{1, 2, 3};
        SnapshotWriter.PreparedArchive prepared = fixture.prepared("", identityHmac);
        fixture.stubDecision(11L, 7L);
        when(fixture.pending.get(11L)).thenReturn(Optional.of(
            new PendingImportStore.Entry(fixture.document, 7L, 3L, NOW.plusSeconds(1_800))));
        when(fixture.writer.prepare(fixture.document)).thenReturn(prepared);
        when(fixture.persons.lockActiveById(1L, 7L)).thenReturn(new PersonMapper.ActivePerson(
            7L, "", PERSON_NAME, PERSON_NAME, identityHmac, IDENTITY_SUFFIX, 3L, NOW.minusSeconds(60)));
        when(fixture.persons.softDeleteForOverwrite(7L, 3L, 9L)).thenReturn(1);
        when(fixture.writer.insert(prepared, 9L, 4L, 7L)).thenReturn(22L);
        when(fixture.persons.linkReplacement(7L, 22L, 9L)).thenReturn(1);

        try (var logs = LogCapture.attach(ImportItemService.class)) {
            fixture.service.decide(11L, "OVERWRITE", 9L);

            assertThat(logs.joined())
                .contains("event=import_decision_started", "decision=OVERWRITE",
                    "event=import_decision_completed", "result=OVERWRITTEN", "personId=22", "durationMs=");
            assertItemDataRedacted(logs.joined());
        }
    }

    @Test
    void rejectsAnUnknownDecisionWithoutLoggingItsRawValue() {
        ItemFixture fixture = new ItemFixture();
        String invalidDecision = "invalid-decision-secret-sentinel";

        try (var logs = LogCapture.attach(ImportItemService.class)) {
            assertThatThrownBy(() -> fixture.service.decide(11L, invalidDecision, 9L))
                .isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=import_decision_started", "decision=INVALID",
                    "event=import_decision_failed", "code=INVALID_IMPORT_DECISION", "durationMs=")
                .doesNotContain(invalidDecision);
        }
    }

    @Test
    void logsHistoryPaginationWithoutMappedRows() {
        ImportMapper mapper = mock(ImportMapper.class);
        AuditService audit = mock(AuditService.class);
        when(mapper.countHistory(1L)).thenReturn(21L);
        when(mapper.findHistory(1L, 20L, 20)).thenReturn(List.of(new ImportMapper.HistoryRow(
            41L, 31L, "operator-name-secret-sentinel", FILE_NAME, "FAILED",
            "TEMPLATE_INVALID", CELL_MESSAGE, NOW)));
        ImportHistoryService service = new ImportHistoryService(mapper, properties(), audit);

        try (var logs = LogCapture.attach(ImportHistoryService.class)) {
            service.history(1, 20, 9L);

            assertThat(logs.joined())
                .contains("event=import_history_started", "operatorId=9", "page=1", "size=20",
                    "event=import_history_completed",
                    "returnedCount=1", "total=21", "durationMs=")
                .doesNotContain("operator-name-secret-sentinel", FILE_NAME, CELL_MESSAGE);
        }
    }

    @Test
    void logsHistoryValidationFailureWithOnlyAFixedCode() {
        ImportHistoryService service = new ImportHistoryService(mock(ImportMapper.class), properties(),
            mock(AuditService.class));

        try (var logs = LogCapture.attach(ImportHistoryService.class)) {
            assertThatThrownBy(() -> service.history(-1, 20, 9L)).isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=import_history_started", "operatorId=9", "page=-1", "size=20",
                    "event=import_history_failed", "code=VALIDATION_ERROR", "durationMs=");
        }
    }

    @Test
    void logsUnknownBatchFailureWithoutItsMessage() {
        ImportMapper mapper = mock(ImportMapper.class);
        ImportItemService items = mock(ImportItemService.class);
        doAnswer(invocation -> {
            ((ImportMapper.BatchInsert) invocation.getArgument(0)).id = 31L;
            return 1;
        }).when(mapper).insertBatch(any());
        when(items.precheck(anyLong(), any(Integer.class), any(), any(), anyLong()))
            .thenReturn(new ImportDtos.ItemResult(41L, FILE_NAME, "IMPORTED", "IMPORTED", "", null));
        doThrow(new IllegalStateException("batch-message-secret-sentinel"))
            .when(mapper).refreshBatch(31L);
        ImportBatchService service = new ImportBatchService(mapper, items, properties());
        var file = new MockMultipartFile("files", FILE_NAME,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1});

        try (var logs = LogCapture.attach(ImportBatchService.class)) {
            assertThatThrownBy(() -> service.precheck(List.of(file), 9L))
                .isInstanceOf(IllegalStateException.class);

            assertThat(logs.joined())
                .contains("event=import_batch_started", "batchId=31", "fileCount=1",
                    "event=import_batch_failed", "code=INTERNAL_ERROR",
                    "exceptionType=java.lang.IllegalStateException", "durationMs=")
                .doesNotContain("batch-message-secret-sentinel", FILE_NAME);
        }
    }

    private static void assertItemDataRedacted(String logText) {
        assertThat(logText).doesNotContain(FILE_NAME, PERSON_NAME, IDENTITY_SUFFIX, EMPLOYEE_NO, CELL_MESSAGE,
            "school-secret-sentinel", "work-secret-sentinel", "reward-secret-sentinel",
            "family-name-secret-sentinel");
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1L, null,
            new ArchiveProperties.Excel(12_582_912L, 5_242_880L, 500,
                "classpath:/excel/personnel-archive-template-v1.xlsx"), null);
    }

    private static final class ItemFixture {
        private final ImportMapper imports = mock(ImportMapper.class);
        private final PersonMapper persons = mock(PersonMapper.class);
        private final SnapshotWriter writer = mock(SnapshotWriter.class);
        private final ExcelArchiveReader reader = mock(ExcelArchiveReader.class);
        private final AuditService audit = mock(AuditService.class);
        private final PendingImportStore pending = mock(PendingImportStore.class);
        private final ArchiveDocument document = document();
        private final ImportItemService service = new ImportItemService(imports, persons, writer, reader,
            properties(), audit, Clock.fixed(NOW, ZoneOffset.UTC), pending);

        private void stubParsedDocument(SnapshotWriter.PreparedArchive prepared) {
            when(reader.read(any(), anyLong(), any())).thenReturn(document);
            when(writer.prepare(document)).thenReturn(prepared);
        }

        private SnapshotWriter.PreparedArchive prepared(String employeeNo, byte[] identityHmac) {
            return new SnapshotWriter.PreparedArchive(document, employeeNo == null ? "" : employeeNo,
                PERSON_NAME, PERSON_NAME, identityHmac == null ? null : "identity-secret-sentinel", identityHmac);
        }

        private void assignItemId(long itemId) {
            doAnswer(invocation -> {
                ((ImportMapper.ItemInsert) invocation.getArgument(0)).id = itemId;
                return 1;
            }).when(imports).insertItem(any());
        }

        private void stubDecision(long itemId, long personId) {
            when(imports.lockDecision(1L, itemId)).thenReturn(new ImportMapper.DecisionRow(
                itemId, 1L, 5L, FILE_NAME, 3L, new byte[]{9}, "PENDING_DECISION",
                personId, 3L, NOW.plusSeconds(1_800)));
        }
    }

    private static ArchiveDocument document() {
        var orderedEducation = new ArchiveDocument.OrderedText(
            0, "2020—2021", "school-secret-sentinel", "raw-education-secret-sentinel", "2020", "2021");
        var orderedWork = new ArchiveDocument.OrderedText(
            0, "2021—2022", "work-secret-sentinel", "raw-work-secret-sentinel", "2021", "2022");
        var orderedReward = new ArchiveDocument.OrderedText(
            0, "2022", "reward-secret-sentinel", "raw-reward-secret-sentinel", "2022", "");
        var family = new ArchiveDocument.FamilyMember(0, "relationship-secret-sentinel",
            "family-name-secret-sentinel", SourceDate.exact("1990-01-02", LocalDate.of(1990, 1, 2)),
            "36", "political-secret-sentinel", "workplace-secret-sentinel");
        return new ArchiveDocument(EMPLOYEE_NO, PERSON_NAME, "identity-secret-sentinel", null, SourceDate.empty(),
            null, null, null, null, SourceDate.empty(), null, null, "address-secret-sentinel", null,
            "phone-secret-sentinel", null, null, null,
            List.of(orderedEducation), List.of(orderedWork), List.of(orderedReward), List.of(family), null,
            PERSON_NAME, SourceDate.exact("2026-08-13", LocalDate.of(2026, 8, 13)));
    }
}
