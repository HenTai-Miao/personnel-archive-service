/* 人员 API DTO：定义默认脱敏详情、按需敏感值、列表和家庭成员匹配结果，不直接暴露数据库实体。 */
package com.company.personnelarchive.person;

import java.util.List;

public final class PersonDtos {
    private PersonDtos() {}

    public record Detail(String id, String employeeNo, String name, String identityCardMasked,
                         PersonalInfo personal, EmploymentDetails employment, EducationDetails education,
                         List<OrderedText> mainEducation, List<OrderedText> workExperiences,
                         List<OrderedText> rewardsPunishments, List<FamilyMember> familyMembers,
                         DeclarationInfo declaration, PhotoMetadata photo, ArchiveMetadata archive) {}

    public record PersonalInfo(String gender, String birthDate, String nation, String nativePlace,
                               String birthPlace, String politicalStatus, String politicalJoinDate,
                               String maritalStatus, String healthStatus, String mailingAddress,
                               String postalCode, String phoneNumberMasked) {}

    public enum SensitiveField { IDENTITY_CARD, PHONE_NUMBER }
    public record SensitiveValue(String value) {}

    public record EmploymentDetails(String organization, String department, String workStartDate,
                                    String dispatchedToBiddingCompanyDate, String position,
                                    String titleOrSkill, String titleOrSkillAcquiredDate) {}

    public record EducationDetails(EducationSummary fullTime, EducationSummary inService) {}
    public record EducationSummary(String institutionName, String departmentAndMajor,
                                   String educationLevel, String degreeName) {}
    public record OrderedText(int sortOrder, String leftText, String rightText) {}
    public record FamilyMember(String name, String relationship, String birthDate, Integer age,
                               String politicalStatus, String organizationAndPosition) {}
    public record DeclarationInfo(String signer, String date) {}
    public record PhotoMetadata(String mimeType, String originalFilename, long byteSize,
                                int widthPixels, int heightPixels) {}
    public record ArchiveMetadata(String templateVersion, long snapshotVersion, String updatedAt) {}

    public record FamilySearchResult(String personId, String personName, String organization,
                                     String department, String position, String titleOrSkill,
                                     String matchedMemberName, String relationship) {}
    public record FamilySearchPage(List<FamilySearchResult> content, int page, int size,
                                   long totalElements, int totalPages) {}
    public record PersonListItem(String id, String employeeNo, String name, String organization,
                                 String department, String position, String titleOrSkill, String updatedAt) {}
    public record PersonListPage(List<PersonListItem> content, int page, int size,
                                 long totalElements, int totalPages) {}
}
