/* 人员详情查询测试：锁定全部导入分区映射、标准业务值还原和照片读取边界。 */
package com.company.personnelarchive.person;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersonQueryServiceTest {
    @Test
    void mapsEveryImportedSectionWhenReadingDetail() {
        // Given
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        IdentityProtection identityProtection = mock(IdentityProtection.class);
        PersonReadMapper.PersonRow personRow = personRow();
        when(mapper.findActive(1, 7)).thenReturn(personRow);
        when(identityProtection.decrypt(personRow.idCardCiphertext())).thenReturn("123456789012345678");
        when(mapper.findEmployment(1, 7)).thenReturn(new PersonReadMapper.EmploymentRow(
            "测试单位", "测试部门", "2020-01", "2021-01",
            "测试职务", "测试技能", "2022-01"));
        when(mapper.findEducation(1, 7)).thenReturn(List.of(
            new PersonReadMapper.EducationRow("FULL_TIME", "全日制大学", "档案学", "本科", "学士", null, null, null, 0),
            new PersonReadMapper.EducationRow("IN_SERVICE", "在职大学", "管理学", "研究生", "硕士", null, null, null, 0),
            new PersonReadMapper.EducationRow("MAIN", null, null, null, null,
                "2020-09", "2024-06", "2020-09—2024-06\t示例大学", 1)));
        when(mapper.findWork(1, 7)).thenReturn(List.of(new PersonReadMapper.WorkRow(
            "2024-07", "至今", "覆盖版本", "2024-07—至今\t覆盖版本", 1)));
        when(mapper.findRewards(1, 7)).thenReturn(List.of(new PersonReadMapper.RewardRow(
            "2025-01", "2025-01\t测试奖励", 1)));
        when(mapper.findFamily(1, 7)).thenReturn(List.of(new PersonReadMapper.FamilyRow(
            11, "测试称谓", "测试乙", "2000-01", "35", "测试面貌", "测试单位及职务", "", 1)));
        when(mapper.findPhotoMetadata(1, 7)).thenReturn(new PersonReadMapper.PhotoMetadataRow(
            "image/png", "fixture-photo.png", 128, 3, 4));
        var service = new PersonQueryService(mapper, properties(), audit, identityProtection);

        // When
        PersonDtos.Detail detail = service.detail(7, 9);

        // Then
        assertThat(detail.personal().birthDate()).isEqualTo("2000-01");
        assertThat(detail.personal().politicalJoinDate()).isEqualTo("2021-07");
        assertThat(detail.employment().workStartDate()).isEqualTo("2020-01");
        assertThat(detail.education().fullTime().institutionName()).isEqualTo("全日制大学");
        assertThat(detail.education().inService().degreeName()).isEqualTo("硕士");
        assertThat(detail.mainEducation().getFirst().rightText()).isEqualTo("示例大学");
        assertThat(detail.workExperiences().getFirst().leftText()).isEqualTo("2024-07—至今");
        assertThat(detail.workExperiences().getFirst().rightText()).isEqualTo("覆盖版本");
        assertThat(detail.rewardsPunishments().getFirst().rightText()).isEqualTo("测试奖励");
        assertThat(detail.familyMembers().getFirst().politicalStatus()).isEqualTo("测试面貌");
        assertThat(detail.familyMembers().getFirst().birthDate()).isEqualTo("2000-01");
        assertThat(detail.familyMembers().getFirst().age()).isEqualTo("35");
        assertThat(detail.declaration().signer()).isEqualTo("测试甲");
        assertThat(detail.declaration().date()).isEqualTo("2026-07-22");
        assertThat(detail.photo().mimeType()).isEqualTo("image/png");
        assertThat(detail.archive().snapshotVersion()).isEqualTo(2);
        assertThat(detail.identityCardMasked()).isEqualTo("1234***********678");
        assertThat(detail.personal().phoneNumberMasked()).isEqualTo("1380****000");
        verify(audit).record(9L, "VIEW", "PERSON", 7L, "SUCCESS", true);
    }

    @Test
    void exposesBlankOptionalIdentityFieldsWithoutInventingValues() {
        // Given
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        IdentityProtection identityProtection = mock(IdentityProtection.class);
        when(mapper.findActive(1, 8)).thenReturn(new PersonReadMapper.PersonRow(
            8, "", "", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            "V1", 1, Instant.parse("2026-07-22T03:00:00Z")));
        when(mapper.findEducation(1, 8)).thenReturn(List.of());
        when(mapper.findWork(1, 8)).thenReturn(List.of());
        when(mapper.findRewards(1, 8)).thenReturn(List.of());
        when(mapper.findFamily(1, 8)).thenReturn(List.of());
        var service = new PersonQueryService(mapper, properties(), audit, identityProtection);

        // When
        PersonDtos.Detail detail = service.detail(8, 9);

        // Then
        assertThat(detail.employeeNo()).isEmpty();
        assertThat(detail.name()).isEmpty();
        assertThat(detail.identityCardMasked()).isEmpty();
        assertThat(detail.personal().phoneNumberMasked()).isEmpty();
        verifyNoInteractions(identityProtection);
        verify(audit).record(9L, "VIEW", "PERSON", 8L, "SUCCESS", true);
    }

    @Test
    void revealsFullIdentityOnlyThroughExplicitSensitiveRead() {
        // Given
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        IdentityProtection identityProtection = mock(IdentityProtection.class);
        PersonReadMapper.PersonRow person = personRow();
        when(mapper.findActive(1, 7)).thenReturn(person);
        when(identityProtection.decrypt(person.idCardCiphertext())).thenReturn("123456789012345678");
        var service = new PersonQueryService(mapper, properties(), audit, identityProtection);

        // When
        PersonDtos.SensitiveValue result = service.sensitiveValue(
            7, PersonDtos.SensitiveField.IDENTITY_CARD, 9);

        // Then
        assertThat(result.value()).isEqualTo("123456789012345678");
        verify(audit).recordSensitiveView(9L, 7L, "IDENTITY_CARD");
    }

    @Test
    void revealsFullPhoneOnlyThroughExplicitSensitiveRead() {
        // Given
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        IdentityProtection identityProtection = mock(IdentityProtection.class);
        when(mapper.findActive(1, 7)).thenReturn(personRow());
        var service = new PersonQueryService(mapper, properties(), audit, identityProtection);

        // When
        PersonDtos.SensitiveValue result = service.sensitiveValue(
            7, PersonDtos.SensitiveField.PHONE_NUMBER, 9);

        // Then
        assertThat(result.value()).isEqualTo("13800138000");
        verifyNoInteractions(identityProtection);
        verify(audit).recordSensitiveView(9L, 7L, "PHONE_NUMBER");
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
        var service = new PersonQueryService(
            mapper, properties(), audit, mock(IdentityProtection.class));

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
        var service = new PersonQueryService(
            mapper, properties(), audit, mock(IdentityProtection.class));

        // When / Then
        assertThatThrownBy(() -> service.photo(7, 9))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(audit);
    }

    @Test
    void escapesLikeWildcardsWithPortableEscapeCharacter() {
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        AuditService audit = mock(AuditService.class);
        when(mapper.findActivePersons(1, "A!!!%!_", "", "", 0, 20))
            .thenReturn(List.of());
        var service = new PersonQueryService(
            mapper, properties(), audit, mock(IdentityProtection.class));

        service.list("A!%_", "", "", 0, 20, 9);

        verify(mapper).countActivePersons(1, "A!!!%!_", "", "");
        verify(mapper).findActivePersons(1, "A!!!%!_", "", "", 0, 20);
    }

    private static PersonReadMapper.PersonRow personRow() {
        return new PersonReadMapper.PersonRow(
            7, "E-001", "测试甲", new byte[]{1}, "0012", "女", "2000-01", "汉族",
            "湖南长沙", "湖南长沙", "中共党员", "2021-07", "已婚", "健康",
            "测试地址", "410000", "13800138000", "测试甲", "2026-07-22",
            "V1", 2, Instant.parse("2026-07-22T03:00:00Z"));
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1, null, null, null);
    }
}
