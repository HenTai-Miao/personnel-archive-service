package com.company.personnelarchive.audit;

import com.company.personnelarchive.common.RequestCorrelation;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AuditRequestCorrelationTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesTheCurrentHttpRequestIdForEveryAuditShape() {
        String requestId = "795a072f-3c16-489f-b209-8b12455c3546";
        MDC.put(RequestCorrelation.MDC_KEY, requestId);
        List<String> writtenIds = new ArrayList<>();
        AuditService audit = serviceCapturing(writtenIds);

        audit.record(9L, "VIEW", "PERSON", 7L, "SUCCESS", true);
        audit.recordImport(9L, "IMPORT", 7L, 5L, 11L, "IMPORTED", true);
        audit.recordOverwrite(9L, 7L, 8L, 5L, 11L);
        audit.recordSensitiveView(9L, 7L, "IDENTITY_CARD");

        assertThat(writtenIds).containsOnly(requestId).hasSize(4);
    }

    @Test
    void createsAValidFallbackIdOutsideAnHttpRequest() {
        List<String> writtenIds = new ArrayList<>();
        AuditService audit = serviceCapturing(writtenIds);

        audit.record(9L, "VIEW", "PERSON", 7L, "SUCCESS", true);

        assertThat(writtenIds).singleElement().satisfies(value ->
            assertThat(UUID.fromString(value)).isNotNull());
    }

    private static AuditService serviceCapturing(List<String> ids) {
        AuditMapper mapper = mock(AuditMapper.class);
        doAnswer(invocation -> {
            ids.add(invocation.getArgument(11, String.class));
            return 1;
        }).when(mapper).insert(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return new AuditService(mapper, new ArchiveProperties(1, null, null, null));
    }
}
