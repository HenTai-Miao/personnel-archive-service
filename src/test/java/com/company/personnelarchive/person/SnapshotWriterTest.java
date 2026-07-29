/* 快照写入测试：锁定空字段可导入及部分填写家庭成员的原样持久化。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.archiveimport.ArchiveDocument;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SnapshotWriterTest {
    private final PersonMapper mapper = mock(PersonMapper.class);
    private final IdentityProtection protection = mock(IdentityProtection.class);
    private final SnapshotWriter writer = new SnapshotWriter(mapper, protection, properties(),
        Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void prepareAcceptsBlankEmployeeNameAndIdentity() {
        // Given
        ArchiveDocument document = document(List.of());

        // When
        SnapshotWriter.PreparedArchive prepared = writer.prepare(document);

        // Then
        assertThat(prepared.employeeNo()).isEmpty();
        assertThat(prepared.name()).isEmpty();
        assertThat(prepared.normalizedName()).isEmpty();
        assertThat(prepared.identityCard()).isNull();
        assertThat(prepared.identityHmac()).isNull();
        verifyNoInteractions(protection);
    }

    @Test
    void insertPreservesPartiallyFilledFamilyMember() {
        // Given
        ArchiveDocument document = document(List.of(
            new ArchiveDocument.FamilyMember(0, "", "", null, "群众", "")));
        SnapshotWriter.PreparedArchive prepared = new SnapshotWriter.PreparedArchive(
            document, "", "", "", null, null);
        doAnswer(invocation -> {
            ((PersonMapper.PersonInsert) invocation.getArgument(0)).id = 7L;
            return 1;
        }).when(mapper).insertPerson(any());

        // When
        writer.insert(prepared, 9, 1, null);

        // Then
        ArgumentCaptor<PersonMapper.PersonInsert> person = ArgumentCaptor.forClass(PersonMapper.PersonInsert.class);
        verify(mapper).insertPerson(person.capture());
        assertThat(person.getValue().employeeNo).isEmpty();
        assertThat(person.getValue().personName).isEmpty();
        assertThat(person.getValue().idCardCiphertext).isNull();
        assertThat(person.getValue().idCardHmac).isNull();
        assertThat(person.getValue().idCardLastFour).isNull();
        verify(mapper).insertFamily(1, 7, "", "", "", null, "群众", null,
            "\t\t\t群众\t", 1);
    }

    @Test
    void insertsEveryFamilyMemberBeyondTemplateSixRows() {
        List<ArchiveDocument.FamilyMember> members = IntStream.range(0, 10)
            .mapToObj(index -> new ArchiveDocument.FamilyMember(
                index, "称谓" + index, "成员" + index, null, "", ""))
            .toList();
        ArchiveDocument document = document(members);
        doAnswer(invocation -> {
            ((PersonMapper.PersonInsert) invocation.getArgument(0)).id = 7L;
            return 1;
        }).when(mapper).insertPerson(any());

        SnapshotWriter.PreparedArchive prepared = writer.prepare(document);
        writer.insert(prepared, 9, 1, null);

        ArgumentCaptor<Integer> orders = ArgumentCaptor.forClass(Integer.class);
        verify(mapper, times(10)).insertFamily(eq(1L), eq(7L), anyString(), anyString(), anyString(),
            isNull(), isNull(), isNull(), anyString(), orders.capture());
        assertThat(orders.getAllValues()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    private static ArchiveDocument document(List<ArchiveDocument.FamilyMember> family) {
        return new ArchiveDocument("", "", "", null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, List.of(), List.of(), List.of(),
            family, null, "", null);
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000,
                "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500,
                "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
    }
}
