package com.company.personnelarchive.archiveexport;

import com.company.personnelarchive.archiveimport.ExcelArchiveReader;
import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.person.IdentityProtection;
import com.company.personnelarchive.person.PersonReadMapper;
import com.company.personnelarchive.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportOperationalLoggingTest {
    private static final String PERSON_NAME = "person-name-secret-sentinel";
    private static final String IDENTITY = "identity-secret-sentinel";
    private static final String PHONE = "phone-secret-sentinel";
    private static final String GENERATED_NAME = "generated-name-secret-sentinel.xlsx";
    private static final String FAILURE_MESSAGE = "exception-message-secret-sentinel";
    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));

    @Test
    void logsSingleExportIdsAndByteCountWithoutArchiveFieldsOrFilename() {
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        IdentityProtection protection = mock(IdentityProtection.class);
        AuditService audit = mock(AuditService.class);
        PersonReadMapper.PersonRow person = person();
        stubExportRows(mapper, person);
        when(protection.decrypt(person.idCardCiphertext())).thenReturn(IDENTITY);
        ArchiveExportService service = new ArchiveExportService(mapper, properties(), protection,
            new ExcelArchiveReader(properties()), new DefaultResourceLoader(), audit);

        try (var logs = LogCapture.attach(ArchiveExportService.class)) {
            ArchiveExportService.GeneratedArchive result = service.generate(7L, 9L);

            assertThat(result.bytes()).isNotEmpty();
            assertThat(logs.joined())
                .contains("event=archive_export_started", "operatorId=9", "personId=7",
                    "event=archive_export_completed", "byteCount=", "result=SUCCESS", "durationMs=");
            assertSingleExportDataRedacted(logs.joined());
        }
    }

    @Test
    void logsMissingPersonWithOnlyTheFixedFailureCode() {
        ArchiveExportService service = new ArchiveExportService(mock(PersonReadMapper.class), properties(),
            mock(IdentityProtection.class), mock(ExcelArchiveReader.class), new DefaultResourceLoader(),
            mock(AuditService.class));

        try (var logs = LogCapture.attach(ArchiveExportService.class)) {
            assertThatThrownBy(() -> service.generate(7L, 9L)).isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=archive_export_started", "personId=7", "event=archive_export_failed",
                    "code=NOT_FOUND", "durationMs=");
            assertSingleExportDataRedacted(logs.joined());
        }
    }

    @Test
    void logsTemplateIoFailureWithoutItsExceptionMessage() throws IOException {
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        when(mapper.findActive(1L, 7L)).thenReturn(person());
        ResourceLoader loader = mock(ResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(loader.getResource(anyString())).thenReturn(resource);
        when(resource.exists()).thenReturn(true);
        when(resource.getInputStream()).thenThrow(new IOException(FAILURE_MESSAGE));
        ArchiveExportService service = new ArchiveExportService(mapper, properties(),
            mock(IdentityProtection.class), mock(ExcelArchiveReader.class), loader, mock(AuditService.class));

        try (var logs = LogCapture.attach(ArchiveExportService.class)) {
            assertThatThrownBy(() -> service.generate(7L, 9L)).isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=archive_export_failed", "personId=7", "code=INTERNAL_ERROR",
                    "exceptionType=java.io.IOException", "durationMs=")
                .doesNotContain(FAILURE_MESSAGE);
            assertSingleExportDataRedacted(logs.joined());
        }
    }

    @Test
    void logsCompletedBatchByCountAndAggregateBytesWithoutEntryNames() {
        ArchiveExportService exports = mock(ArchiveExportService.class);
        AuditService audit = mock(AuditService.class);
        when(exports.generateForBatch(7L)).thenReturn(
            new ArchiveExportService.GeneratedArchive(GENERATED_NAME, new byte[]{1, 2}));
        when(exports.generateForBatch(8L)).thenReturn(
            new ArchiveExportService.GeneratedArchive(GENERATED_NAME, new byte[]{3, 4}));
        BatchArchiveExportService service = new BatchArchiveExportService(exports, audit, CLOCK);

        try (var logs = LogCapture.attach(BatchArchiveExportService.class)) {
            BatchArchiveExportService.GeneratedBundle result = service.generate(List.of("7", "8"), 9L);

            assertThat(result.bytes()).isNotEmpty();
            assertThat(logs.joined())
                .contains("event=batch_export_started", "operatorId=9", "itemCount=2",
                    "event=batch_export_completed", "byteCount=", "result=SUCCESS", "durationMs=")
                .doesNotContain(GENERATED_NAME, PERSON_NAME, IDENTITY, PHONE);
        }
    }

    @Test
    void logsPartialBatchFailureWithoutGeneratedNamesOrExceptionMessages() {
        ArchiveExportService exports = mock(ArchiveExportService.class);
        when(exports.generateForBatch(7L)).thenReturn(
            new ArchiveExportService.GeneratedArchive(GENERATED_NAME, new byte[]{1, 2}));
        when(exports.generateForBatch(8L)).thenThrow(
            new BusinessException(com.company.personnelarchive.common.ErrorCode.NOT_FOUND, FAILURE_MESSAGE));
        BatchArchiveExportService service = new BatchArchiveExportService(
            exports, mock(AuditService.class), CLOCK);

        try (var logs = LogCapture.attach(BatchArchiveExportService.class)) {
            assertThatThrownBy(() -> service.generate(List.of("7", "8"), 9L))
                .isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=batch_export_started", "itemCount=2", "event=batch_export_failed",
                    "code=NOT_FOUND", "durationMs=")
                .doesNotContain(GENERATED_NAME, FAILURE_MESSAGE, PERSON_NAME, IDENTITY, PHONE);
        }
    }

    private static void assertSingleExportDataRedacted(String logText) {
        assertThat(logText).doesNotContain(PERSON_NAME, IDENTITY, PHONE, GENERATED_NAME,
            "employee-number-secret-sentinel", "address-secret-sentinel");
    }

    private static void stubExportRows(PersonReadMapper mapper, PersonReadMapper.PersonRow person) {
        when(mapper.findActive(1L, 7L)).thenReturn(person);
        when(mapper.findEducation(1L, 7L)).thenReturn(List.of());
        when(mapper.findWork(1L, 7L)).thenReturn(List.of());
        when(mapper.findRewards(1L, 7L)).thenReturn(List.of());
        when(mapper.findFamily(1L, 7L)).thenReturn(List.of());
    }

    private static PersonReadMapper.PersonRow person() {
        return new PersonReadMapper.PersonRow(7L, "employee-number-secret-sentinel", PERSON_NAME,
            new byte[]{1, 2, 3}, "0012", null, null, null, null, null, null, null, null, null,
            "address-secret-sentinel", null, PHONE, PERSON_NAME, "2026-08-13", "V1", 1L, NOW);
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1L,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1_000,
                "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912L, 5_242_880L, 500,
                "classpath:/excel/personnel-archive-template-v1.xlsx"), null);
    }
}
