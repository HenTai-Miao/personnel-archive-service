/* 人员详情查询测试：锁定全部导入分区映射、原文列还原和照片读取边界。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersonQueryServiceTest {
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-22T03:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void mapsEveryImportedSectionWhenReadingDetail() {
        // Given
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        when(mapper.findActive(1, 7)).thenReturn(personRow());
        when(mapper.findEmployment(1, 7)).thenReturn(new PersonReadMapper.EmploymentRow(
            "测试单位", "测试部门", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1),
            "测试职务", "测试技能", LocalDate.of(2022, 1, 1)));
        when(mapper.findEducation(1, 7)).thenReturn(List.of(
            new PersonReadMapper.EducationRow("FULL_TIME", "全日制大学", "档案学", "本科", "学士", null, null, null, 0),
            new PersonReadMapper.EducationRow("IN_SERVICE", "在职大学", "管理学", "研究生", "硕士", null, null, null, 0),
            new PersonReadMapper.EducationRow("MAIN", null, null, null, null,
                LocalDate.of(2020, 9, 1), LocalDate.of(2024, 6, 1), "2020.09-2024.06\t示例大学", 1)));
        when(mapper.findWork(1, 7)).thenReturn(List.of(new PersonReadMapper.WorkRow(
            LocalDate.of(2024, 7, 1), null, "覆盖版本", "2024.07-至今\t覆盖版本", 1)));
        when(mapper.findRewards(1, 7)).thenReturn(List.of(new PersonReadMapper.RewardRow(
            LocalDate.of(2025, 1, 1), "2025.01\t测试奖励", 1)));
        when(mapper.findFamily(1, 7)).thenReturn(List.of(new PersonReadMapper.FamilyRow(
            11, "测试称谓", "测试乙", LocalDate.of(2000, 7, 23), "测试面貌", "测试单位及职务", "", 1)));
        when(mapper.findPhotoMetadata(1, 7)).thenReturn(new PersonReadMapper.PhotoMetadataRow(
            "image/png", "fixture-photo.png", 128, 3, 4));
        var service = new PersonQueryService(mapper, properties(), CLOCK, audit);

        // When
        PersonDtos.Detail detail = service.detail(7, 9);

        // Then
        assertThat(detail.personal().politicalJoinDate()).isEqualTo("2021-07-01");
        assertThat(detail.employment().workStartDate()).isEqualTo("2020-01-01");
        assertThat(detail.education().fullTime().institutionName()).isEqualTo("全日制大学");
        assertThat(detail.education().inService().degreeName()).isEqualTo("硕士");
        assertThat(detail.mainEducation().getFirst().rightText()).isEqualTo("示例大学");
        assertThat(detail.workExperiences().getFirst().rightText()).isEqualTo("覆盖版本");
        assertThat(detail.rewardsPunishments().getFirst().rightText()).isEqualTo("测试奖励");
        assertThat(detail.familyMembers().getFirst().politicalStatus()).isEqualTo("测试面貌");
        assertThat(detail.familyMembers().getFirst().age()).isEqualTo(25);
        assertThat(detail.declaration().signer()).isEqualTo("测试甲");
        assertThat(detail.photo().mimeType()).isEqualTo("image/png");
        assertThat(detail.archive().snapshotVersion()).isEqualTo(2);
        verify(audit).record(9L, "VIEW", "PERSON", 7L, "SUCCESS", true);
    }

    @Test
    void exposesBlankOptionalIdentityFieldsWithoutInventingValues() {
        // Given
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        when(mapper.findActive(1, 8)).thenReturn(new PersonReadMapper.PersonRow(
            8, "", "", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            "V1", 1, Instant.parse("2026-07-22T03:00:00Z")));
        when(mapper.findEducation(1, 8)).thenReturn(List.of());
        when(mapper.findWork(1, 8)).thenReturn(List.of());
        when(mapper.findRewards(1, 8)).thenReturn(List.of());
        when(mapper.findFamily(1, 8)).thenReturn(List.of());
        var service = new PersonQueryService(mapper, properties(), CLOCK, audit);

        // When
        PersonDtos.Detail detail = service.detail(8, 9);

        // Then
        assertThat(detail.employeeNo()).isEmpty();
        assertThat(detail.name()).isEmpty();
        assertThat(detail.identityCardMasked()).isEmpty();
        verify(audit).record(9L, "VIEW", "PERSON", 8L, "SUCCESS", true);
    }

    @Test
    void returnsOriginalPhotoBytesWhenReadingPhoto() {
        // Given
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        when(mapper.findActive(1, 7)).thenReturn(personRow());
        when(mapper.findPhoto(1, 7)).thenReturn(new PersonReadMapper.PhotoRow(
            new byte[]{1, 2, 3}, "image/png", "fixture-photo.png", 3, 3, 4, new byte[]{9},
            8, 4, 0, 0, 9, 7, 0, 0, "MOVE_DONT_RESIZE"));
        var service = new PersonQueryService(mapper, properties(), CLOCK, audit);

        // When
        PersonQueryService.PhotoContent photo = service.photo(7, 9);

        // Then
        assertThat(photo.bytes()).containsExactly(1, 2, 3);
        assertThat(photo.mimeType()).isEqualTo("image/png");
        assertThat(photo.filename()).isEqualTo("fixture-photo.png");
        verify(audit).record(9L, "VIEW", "PERSON", 7L, "SUCCESS", true);
    }

    @Test
    void recordsNoSuccessfulViewWhenPhotoIsMissing() {
        // Given
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        when(mapper.findActive(1, 7)).thenReturn(personRow());
        var service = new PersonQueryService(mapper, properties(), CLOCK, audit);

        // When / Then
        assertThatThrownBy(() -> service.photo(7, 9))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(audit);
    }

    private static PersonReadMapper.PersonRow personRow() {
        return new PersonReadMapper.PersonRow(
            7, "E-001", "测试甲", new byte[]{1}, "0012", "女", LocalDate.of(2000, 1, 1), "汉族",
            "湖南长沙", "湖南长沙", "中共党员", LocalDate.of(2021, 7, 1), "已婚", "健康",
            "测试地址", "410000", "00000002", "测试甲", LocalDate.of(2026, 7, 22), "V1", 2,
            Instant.parse("2026-07-22T03:00:00Z"));
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1, null, null, null);
    }
}
