package com.company.personnelarchive.person;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.support.LogCapture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonOperationalLoggingTest {
    private static final String NAME = "person-name-secret-sentinel";
    private static final String EMPLOYEE = "employee-number-secret-sentinel";
    private static final String PHONE = "phone-secret-sentinel";
    private static final String IDENTITY = "identity-secret-sentinel";
    private static final String ADDRESS = "address-secret-sentinel";
    private static final String DEPARTMENT = "department-secret-sentinel";
    private static final String SEARCH = "family-search-secret-sentinel";
    private static final String FAMILY_NAME = "family-member-secret-sentinel";
    private static final String PHOTO_NAME = "photo-name-secret-sentinel.png";
    private static final String DELETE_REASON = "delete-reason-secret-sentinel";
    private static final Instant UPDATED_AT = Instant.parse("2026-08-13T08:00:00Z");

    @Test
    void logsListPaginationWithoutFiltersOrReturnedRows() {
        QueryFixture fixture = new QueryFixture();
        when(fixture.mapper.countActivePersons(1L, NAME, EMPLOYEE, DEPARTMENT)).thenReturn(21L);
        when(fixture.mapper.findActivePersons(1L, NAME, EMPLOYEE, DEPARTMENT, 20L, 20)).thenReturn(List.of(
            new PersonReadMapper.PersonListRow(7L, EMPLOYEE, NAME, "organization-secret-sentinel", DEPARTMENT,
                "position-secret-sentinel", "title-secret-sentinel", UPDATED_AT)));

        try (var logs = LogCapture.attach(PersonQueryService.class)) {
            fixture.service.list(NAME, EMPLOYEE, DEPARTMENT, 1, 20, 9L);

            assertThat(logs.joined())
                .contains("event=person_list_started", "operatorId=9", "page=1", "size=20",
                    "event=person_list_completed",
                    "returnedCount=1", "total=21", "durationMs=");
            assertSensitiveDataRedacted(logs.joined());
        }
    }

    @Test
    void logsDetailByInternalIdsWithoutTheMappedArchive() {
        QueryFixture fixture = new QueryFixture();
        fixture.stubActive(7L);
        fixture.stubEmptyDetail(7L);
        when(fixture.identity.decrypt(new byte[]{1, 2, 3})).thenReturn(IDENTITY);
        when(fixture.mapper.findPhotoMetadata(1L, 7L)).thenReturn(
            new PersonReadMapper.PhotoMetadataRow("image/png", PHOTO_NAME, 3L, 1, 1));

        try (var logs = LogCapture.attach(PersonQueryService.class)) {
            fixture.service.detail(7L, 9L);

            assertThat(logs.joined())
                .contains("event=person_detail_started", "operatorId=9", "personId=7",
                    "event=person_detail_completed", "durationMs=");
            assertSensitiveDataRedacted(logs.joined());
        }
    }

    @Test
    void logsFamilyPaginationWithoutSearchTermsOrMatches() {
        QueryFixture fixture = new QueryFixture();
        when(fixture.mapper.countFamily(1L, SEARCH)).thenReturn(2L);
        when(fixture.mapper.searchFamily(1L, SEARCH, 0, 20)).thenReturn(List.of(
            new PersonReadMapper.FamilySearchRow(7L, NAME, EMPLOYEE, "organization-secret-sentinel", DEPARTMENT,
                "position-secret-sentinel", "title-secret-sentinel", 17L, FAMILY_NAME,
                "relationship-secret-sentinel")));

        try (var logs = LogCapture.attach(PersonQueryService.class)) {
            fixture.service.searchFamily(SEARCH, 0, 20);

            assertThat(logs.joined())
                .contains("event=family_search_started", "page=0", "size=20",
                    "event=family_search_completed", "returnedCount=1",
                    "total=2", "durationMs=");
            assertSensitiveDataRedacted(logs.joined());
        }
    }

    @Test
    void logsSensitiveViewsByFieldEnumWithoutValues() {
        QueryFixture fixture = new QueryFixture();
        fixture.stubActive(7L);
        when(fixture.identity.decrypt(new byte[]{1, 2, 3})).thenReturn(IDENTITY);

        try (var logs = LogCapture.attach(PersonQueryService.class)) {
            fixture.service.sensitiveValue(7L, PersonDtos.SensitiveField.IDENTITY_CARD, 9L);
            fixture.service.sensitiveValue(7L, PersonDtos.SensitiveField.PHONE_NUMBER, 9L);

            assertThat(logs.joined())
                .contains("event=sensitive_value_started", "operatorId=9", "personId=7",
                    "event=sensitive_value_completed",
                    "field=IDENTITY_CARD", "field=PHONE_NUMBER", "durationMs=");
            assertSensitiveDataRedacted(logs.joined());
        }
    }

    @Test
    void logsPhotoByteCountButNotFilenameOrBytesAndHandlesMissingPhotoSafely() {
        QueryFixture fixture = new QueryFixture();
        fixture.stubActive(7L);
        fixture.stubActive(8L);
        when(fixture.mapper.findPhoto(1L, 7L)).thenReturn(new PersonReadMapper.PhotoRow(
            new byte[]{101, 102, 103}, "image/png", PHOTO_NAME, 3L, 1, 1, new byte[]{9},
            0, 0, 0, 0, 1, 1, 0, 0, "MOVE_DONT_RESIZE"));

        try (var logs = LogCapture.attach(PersonQueryService.class)) {
            fixture.service.photo(7L, 9L);
            assertThatThrownBy(() -> fixture.service.photo(8L, 9L)).isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=photo_started", "operatorId=9", "personId=7",
                    "event=photo_completed", "byteCount=3", "event=photo_failed", "personId=8",
                    "code=NOT_FOUND", "durationMs=")
                .doesNotContain(PHOTO_NAME, "101", "102", "103");
            assertSensitiveDataRedacted(logs.joined());
        }
    }

    @Test
    void logsKnownReadFailuresForEveryOperation() {
        QueryFixture fixture = new QueryFixture();

        try (var logs = LogCapture.attach(PersonQueryService.class)) {
            assertThatThrownBy(() -> fixture.service.list(NAME, EMPLOYEE, DEPARTMENT, -1, 20, 9L))
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> fixture.service.searchFamily(" ", 0, 20))
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> fixture.service.detail(7L, 9L))
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> fixture.service.sensitiveValue(
                7L, PersonDtos.SensitiveField.IDENTITY_CARD, 9L)).isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=person_list_started", "event=person_list_failed", "code=VALIDATION_ERROR",
                    "event=family_search_started", "event=family_search_failed",
                    "event=person_detail_started", "event=person_detail_failed", "code=NOT_FOUND",
                    "event=sensitive_value_started", "event=sensitive_value_failed", "durationMs=");
            assertSensitiveDataRedacted(logs.joined());
        }
    }

    @Test
    void logsUnknownReadFailureWithoutItsMessage() {
        QueryFixture fixture = new QueryFixture();
        when(fixture.mapper.countActivePersons(1L, NAME, EMPLOYEE, DEPARTMENT))
            .thenThrow(new IllegalStateException("query-message-secret-sentinel"));

        try (var logs = LogCapture.attach(PersonQueryService.class)) {
            assertThatThrownBy(() -> fixture.service.list(NAME, EMPLOYEE, DEPARTMENT, 0, 20, 9L))
                .isInstanceOf(IllegalStateException.class);

            assertThat(logs.joined())
                .contains("event=person_list_started", "event=person_list_failed", "code=INTERNAL_ERROR",
                    "exceptionType=java.lang.IllegalStateException", "durationMs=")
                .doesNotContain("query-message-secret-sentinel");
            assertSensitiveDataRedacted(logs.joined());
        }
    }

    @Test
    void logsDeletionSuccessAndFixedFailuresWithoutTheReason() {
        PersonMapper persons = mock(PersonMapper.class);
        AuditService audit = mock(AuditService.class);
        when(persons.softDeleteActive(1L, 7L, 9L, DELETE_REASON)).thenReturn(1);
        when(persons.softDeleteActive(1L, 8L, 9L, DELETE_REASON)).thenReturn(0);
        PersonDeletionService service = new PersonDeletionService(persons, audit, properties());

        try (var logs = LogCapture.attach(PersonDeletionService.class)) {
            service.delete(7L, DELETE_REASON, 9L);
            assertThatThrownBy(() -> service.delete(9L, " \n\t", 9L)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.delete(8L, DELETE_REASON, 9L)).isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=person_deletion_started", "operatorId=9", "personId=7",
                    "event=person_deletion_completed", "event=person_deletion_failed",
                    "code=VALIDATION_ERROR", "code=NOT_FOUND", "durationMs=")
                .doesNotContain(DELETE_REASON);
        }
    }

    private static void assertSensitiveDataRedacted(String logText) {
        assertThat(logText).doesNotContain(NAME, EMPLOYEE, PHONE, IDENTITY, ADDRESS, DEPARTMENT, SEARCH,
            FAMILY_NAME, PHOTO_NAME, "organization-secret-sentinel", "position-secret-sentinel",
            "title-secret-sentinel", "relationship-secret-sentinel");
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1L, null, null, null);
    }

    private static final class QueryFixture {
        private final PersonReadMapper mapper = mock(PersonReadMapper.class);
        private final AuditService audit = mock(AuditService.class);
        private final IdentityProtection identity = mock(IdentityProtection.class);
        private final PersonQueryService service = new PersonQueryService(mapper, properties(), audit, identity);

        private void stubActive(long personId) {
            when(mapper.findActive(1L, personId)).thenReturn(new PersonReadMapper.PersonRow(
                personId, EMPLOYEE, NAME, new byte[]{1, 2, 3}, "0012", "gender-secret-sentinel",
                "2000-01-01", "nation-secret-sentinel", "native-secret-sentinel", "birth-secret-sentinel",
                "political-secret-sentinel", "2020-01", "marital-secret-sentinel", "health-secret-sentinel",
                ADDRESS, "postal-secret-sentinel", PHONE, NAME, "2026-08-13", "V1", 1L, UPDATED_AT));
        }

        private void stubEmptyDetail(long personId) {
            when(mapper.findEducation(1L, personId)).thenReturn(List.of());
            when(mapper.findWork(1L, personId)).thenReturn(List.of());
            when(mapper.findRewards(1L, personId)).thenReturn(List.of());
            when(mapper.findFamily(1L, personId)).thenReturn(List.of());
        }
    }
}
