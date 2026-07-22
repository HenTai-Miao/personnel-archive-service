/* 导入领域文档：承载从工作簿读取的完整数据，并在写库前执行模板容量校验。 */
package com.company.personnelarchive.archiveimport;

import java.time.LocalDate;
import java.util.List;

public record ArchiveDocument(
    String employeeNo, String name, String identityCard, String gender, LocalDate birthDate,
    String ethnicity, String nativePlace, String birthPlace, String politicalStatus, LocalDate politicalJoinDate,
    String maritalStatus, String healthStatus, String address, String postalCode, String phone,
    Employment employment, EducationSummary fullTimeEducation, EducationSummary inServiceEducation,
    List<OrderedText> mainEducation, List<OrderedText> workExperiences, List<OrderedText> rewardsPunishments,
    List<FamilyMember> familyMembers, Photo photo, String declarationSigner, LocalDate declarationDate
) {
    public record Employment(String organizationDepartment, LocalDate workStartDate, LocalDate dispatchDate,
                             String position, String titleSkill, LocalDate titleObtainedDate) {}
    public record EducationSummary(String school, String education, String major, String degree) {}
    public record OrderedText(int sortOrder, String leftText, String rightText, String rawText,
                              LocalDate startDate, LocalDate endDate) {}
    public record FamilyMember(int sortOrder, String relationship, String name, LocalDate birthDate,
                               String politicalStatus, String workplacePosition) {}
    public record Photo(byte[] bytes, String mimeType, String originalFilename, int width, int height,
                        PhotoAnchor anchor) {}
    public record PhotoAnchor(int col1, int row1, int dx1, int dy1, int col2, int row2, int dx2, int dy2,
                              String anchorType) {}

    public void validateCapacity() {
        capacity("主要学历", mainEducation, ExcelTemplateV1.MAIN_EDUCATION_CAPACITY);
        capacity("工作经历", workExperiences, ExcelTemplateV1.WORK_EXPERIENCE_CAPACITY);
        capacity("奖惩情况", rewardsPunishments, ExcelTemplateV1.REWARD_PUNISHMENT_CAPACITY);
        capacity("家庭成员", familyMembers, ExcelTemplateV1.FAMILY_MEMBER_CAPACITY);
    }

    private static void capacity(String name, List<?> rows, int max) {
        if (rows != null && rows.size() > max) {
            throw new com.company.personnelarchive.common.BusinessException(
                com.company.personnelarchive.common.ErrorCode.TEMPLATE_CAPACITY_EXCEEDED,
                name + "最多允许 " + max + " 行");
        }
    }
}
