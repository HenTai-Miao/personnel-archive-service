/* 快照写入测试：锁定空字段可导入及部分填写家庭成员的原样持久化。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.archiveimport.ArchiveDocument;
import com.company.personnelarchive.archiveimport.SourceDate;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
            new ArchiveDocument.FamilyMember(0, "", "", SourceDate.empty(), "", "群众", "")));
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
        verify(mapper).insertFamily(1, 7, "", "", "", PersonMapper.DateProjection.empty(),
            PersonMapper.AgeProjection.empty(), "群众", null,
            "\t\t\t\t群众\t", 1);
    }

    @Test
    void insertPreservesPartialDatesAndSuppliedFamilyAge() {
        ArchiveDocument document = new ArchiveDocument("", "", "", null,
            SourceDate.year("1988", Year.of(1988)), null, null, null, null,
            SourceDate.empty(), null, null, null, null, null, null, null, null, List.of(), List.of(),
            List.of(), List.of(new ArchiveDocument.FamilyMember(0, "妻子", "测试成员",
                SourceDate.month("1990.02", YearMonth.of(1990, 2)), "36", "群众", "测试单位")),
            null, "", SourceDate.empty());
        doAnswer(invocation -> {
            ((PersonMapper.PersonInsert) invocation.getArgument(0)).id = 7L;
            return 1;
        }).when(mapper).insertPerson(any());

        writer.insert(writer.prepare(document), 9, 1, null);

        ArgumentCaptor<PersonMapper.PersonInsert> person = ArgumentCaptor.forClass(PersonMapper.PersonInsert.class);
        verify(mapper).insertPerson(person.capture());
        assertThat(person.getValue().birthDate)
            .isEqualTo(new PersonMapper.DateProjection("1988", 1988, null, null));
        verify(mapper).insertFamily(1, 7, "妻子", "测试成员", "测试成员",
            new PersonMapper.DateProjection("1990-02", 1990, 2, null),
            new PersonMapper.AgeProjection("36", 36), "群众", "测试单位",
            "妻子\t测试成员\t1990-02\t36\t群众\t测试单位", 1);
    }

    @Test
    void insertsEveryStructuredProjectionWithCanonicalText() {
        ArchiveDocument document = new ArchiveDocument("", "", "", null,
            SourceDate.year("二〇二四年", Year.of(2024)), null, null, null, null,
            SourceDate.month("二〇二四年五月", YearMonth.of(2024, 5)), null, null, null, null, null,
            new ArchiveDocument.Employment("测试单位 / 测试部门",
                SourceDate.exact("二〇二〇年五月三日", LocalDate.of(2020, 5, 3)),
                SourceDate.year("二〇二一年", Year.of(2021)), "测试职务", "测试职称",
                SourceDate.month("二〇二二年六月", YearMonth.of(2022, 6))),
            null, null,
            List.of(new ArchiveDocument.OrderedText(0, "二〇一〇年至二〇一四年六月", "测试学历",
                "二〇一〇年至二〇一四年六月\t测试学历", "二〇一〇年", "二〇一四年六月")),
            List.of(new ArchiveDocument.OrderedText(0, "二〇一五年七月至二〇一九年八月九日", "测试工作",
                "二〇一五年七月至二〇一九年八月九日\t测试工作", "二〇一五年七月", "二〇一九年八月九日")),
            List.of(new ArchiveDocument.OrderedText(0, "二〇二〇.九.十", "测试奖励",
                "二〇二〇.九.十\t测试奖励", "二〇二〇.九.十", null)),
            List.of(
                new ArchiveDocument.FamilyMember(0, "妻子", "精确年龄",
                    SourceDate.exact("二〇二四年五月三日", LocalDate.of(2024, 5, 3)),
                    "三十五岁", "群众", "测试单位")),
            null, "测试甲", SourceDate.exact("二〇二四年五月三日", LocalDate.of(2024, 5, 3)));
        doAnswer(invocation -> {
            ((PersonMapper.PersonInsert) invocation.getArgument(0)).id = 7L;
            return 1;
        }).when(mapper).insertPerson(any());

        writer.insert(writer.prepare(document), 9, 1, null);

        ArgumentCaptor<PersonMapper.PersonInsert> person = ArgumentCaptor.forClass(PersonMapper.PersonInsert.class);
        verify(mapper).insertPerson(person.capture());
        assertThat(person.getValue().birthDate)
            .isEqualTo(new PersonMapper.DateProjection("2024", 2024, null, null));
        assertThat(person.getValue().politicalJoinDate)
            .isEqualTo(new PersonMapper.DateProjection("2024-05", 2024, 5, null));
        assertThat(person.getValue().declarationDate)
            .isEqualTo(new PersonMapper.DateProjection("2024-05-03", 2024, 5, 3));
        verify(mapper).insertEmployment(1, 7, "测试单位", "测试部门",
            new PersonMapper.DateProjection("2020-05-03", 2020, 5, 3),
            new PersonMapper.DateProjection("2021", 2021, null, null),
            "测试职务", "测试职称",
            new PersonMapper.DateProjection("2022-06", 2022, 6, null));
        verify(mapper).insertEducation(1, 7, "MAIN", null, null, null, null,
            new PersonMapper.DateProjection("2010", 2010, null, null),
            new PersonMapper.DateProjection("2014-06", 2014, 6, null),
            "2010—2014-06\t测试学历", 1);
        verify(mapper).insertWork(1, 7,
            new PersonMapper.DateProjection("2015-07", 2015, 7, null),
            new PersonMapper.DateProjection("2019-08-09", 2019, 8, 9),
            "测试工作", "2015-07—2019-08-09\t测试工作", 1);
        verify(mapper).insertReward(1, 7,
            new PersonMapper.DateProjection("2020-09-10", 2020, 9, 10),
            "2020-09-10\t测试奖励", 1);
        ArgumentCaptor<PersonMapper.AgeProjection> ages = ArgumentCaptor.forClass(PersonMapper.AgeProjection.class);
        verify(mapper).insertFamily(eq(1L), eq(7L), anyString(), anyString(), anyString(),
            any(PersonMapper.DateProjection.class), ages.capture(), anyString(), anyString(), anyString(), any(Integer.class));
        assertThat(ages.getValue()).isEqualTo(new PersonMapper.AgeProjection("35", 35));
    }

    @Test
    void writesOpenEndedRangeWithCurrentCanonicalSeparator() {
        ArchiveDocument document = new ArchiveDocument("", "", "", null,
            SourceDate.empty(), null, null, null, null, SourceDate.empty(),
            null, null, null, null, null, null, null, null, List.of(),
            List.of(new ArchiveDocument.OrderedText(0, "2024-07—至今", "测试工作",
                "2024-07—至今\t测试工作", "2024-07", "至今")),
            List.of(), List.of(), null, "", SourceDate.empty());
        doAnswer(invocation -> {
            ((PersonMapper.PersonInsert) invocation.getArgument(0)).id = 7L;
            return 1;
        }).when(mapper).insertPerson(any());

        writer.insert(writer.prepare(document), 9, 1, null);

        verify(mapper).insertWork(1, 7,
            new PersonMapper.DateProjection("2024-07", 2024, 7, null),
            new PersonMapper.DateProjection("至今", null, null, null),
            "测试工作", "2024-07—至今\t测试工作", 1);
    }

    @Test
    void rejectsFuzzyFamilyAgeBeforeWritingTheFamilyRow() {
        ArchiveDocument document = document(List.of(
            new ArchiveDocument.FamilyMember(0, "朋友", "模糊年龄", SourceDate.empty(),
                "三十多岁", "群众", "测试单位")));
        doAnswer(invocation -> {
            ((PersonMapper.PersonInsert) invocation.getArgument(0)).id = 7L;
            return 1;
        }).when(mapper).insertPerson(any());

        assertThatThrownBy(() -> writer.insert(writer.prepare(document), 9, 1, null))
            .isInstanceOf(com.company.personnelarchive.archiveimport.SourceValueNormalizationException.class);
        verify(mapper, never()).insertFamily(anyLong(), anyLong(), anyString(), anyString(), anyString(),
            any(), any(), anyString(), anyString(), anyString(), any(Integer.class));
    }

    @Test
    void insertsEveryFamilyMemberBeyondTemplateSixRows() {
        List<ArchiveDocument.FamilyMember> members = IntStream.range(0, 10)
            .mapToObj(index -> new ArchiveDocument.FamilyMember(
                index, "称谓" + index, "成员" + index, SourceDate.empty(), "", "", ""))
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
            eq(PersonMapper.DateProjection.empty()), eq(PersonMapper.AgeProjection.empty()),
            isNull(), isNull(), anyString(), orders.capture());
        assertThat(orders.getAllValues()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    private static ArchiveDocument document(List<ArchiveDocument.FamilyMember> family) {
        return new ArchiveDocument("", "", "", null, SourceDate.empty(), null, null, null, null,
            SourceDate.empty(), null, null, null, null, null, null, null, null, List.of(), List.of(),
            List.of(), family, null, "", SourceDate.empty());
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
