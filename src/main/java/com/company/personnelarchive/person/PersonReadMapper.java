/* 人员读模型映射：为列表、详情、家庭查询和导出提供仅有效快照的专用投影。 */
package com.company.personnelarchive.person;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PersonReadMapper {
    @Select("""
        SELECT id,employee_no,person_name,id_card_ciphertext,id_card_last_four,gender,birth_date,
               nation,native_place,birth_place,political_status,political_join_date,
               marital_status,health_status,mailing_address,postal_code,phone_number,declaration_signer,
               declaration_date,template_version,snapshot_version,updated_at
        FROM pa_person WHERE tenant_id=#{tenantId} AND id=#{personId} AND is_deleted=0
        """)
    PersonRow findActive(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Select("SELECT organization_name,department_name,work_start_date,dispatched_to_bidding_company_date,position_name,professional_title_or_skill,title_or_skill_acquired_date FROM pa_employment WHERE tenant_id=#{tenantId} AND person_id=#{personId}")
    EmploymentRow findEmployment(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Select("SELECT education_type,institution_name,department_and_major,education_level,degree_name,start_date,end_date,raw_text,sort_order FROM pa_education WHERE tenant_id=#{tenantId} AND person_id=#{personId} ORDER BY CASE education_type WHEN 'FULL_TIME' THEN 1 WHEN 'IN_SERVICE' THEN 2 WHEN 'MAIN' THEN 3 ELSE 4 END,sort_order")
    List<EducationRow> findEducation(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Select("SELECT start_date,end_date,organization_and_position,raw_text,sort_order FROM pa_work_experience WHERE tenant_id=#{tenantId} AND person_id=#{personId} ORDER BY sort_order")
    List<WorkRow> findWork(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Select("SELECT occurred_date,raw_text,sort_order FROM pa_reward_punishment WHERE tenant_id=#{tenantId} AND person_id=#{personId} ORDER BY sort_order")
    List<RewardRow> findRewards(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Select("SELECT id,relationship_name,member_name,birth_date,age_text,political_status,organization_and_position,raw_text,sort_order FROM pa_family_member WHERE tenant_id=#{tenantId} AND person_id=#{personId} ORDER BY sort_order")
    List<FamilyRow> findFamily(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Select("SELECT photo_data,mime_type,original_filename,byte_size,width_pixels,height_pixels,sha256,anchor_col1,anchor_row1,anchor_dx1,anchor_dy1,anchor_col2,anchor_row2,anchor_dx2,anchor_dy2,anchor_type FROM pa_person_photo WHERE tenant_id=#{tenantId} AND person_id=#{personId}")
    PhotoRow findPhoto(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Select("SELECT mime_type,original_filename,byte_size,width_pixels,height_pixels FROM pa_person_photo WHERE tenant_id=#{tenantId} AND person_id=#{personId}")
    PhotoMetadataRow findPhotoMetadata(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Select("""
        SELECT p.id,p.employee_no,p.person_name,e.organization_name,e.department_name,e.position_name,
               e.professional_title_or_skill,p.updated_at
        FROM pa_person p
        LEFT JOIN pa_employment e ON e.person_id=p.id AND e.tenant_id=p.tenant_id
        WHERE p.tenant_id=#{tenantId} AND p.is_deleted=0
          AND (#{escapedName}='' OR p.normalized_name LIKE CONCAT('%',#{escapedName},'%') ESCAPE '!')
          AND (#{escapedEmployeeNo}='' OR p.employee_no LIKE CONCAT('%',#{escapedEmployeeNo},'%') ESCAPE '!')
          AND (#{escapedDepartment}='' OR e.organization_name LIKE CONCAT('%',#{escapedDepartment},'%') ESCAPE '!'
               OR e.department_name LIKE CONCAT('%',#{escapedDepartment},'%') ESCAPE '!')
        ORDER BY p.updated_at DESC,p.id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<PersonListRow> findActivePersons(@Param("tenantId") long tenantId,
                                          @Param("escapedName") String escapedName,
                                          @Param("escapedEmployeeNo") String escapedEmployeeNo,
                                          @Param("escapedDepartment") String escapedDepartment,
                                          @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
        SELECT COUNT(*)
        FROM pa_person p
        LEFT JOIN pa_employment e ON e.person_id=p.id AND e.tenant_id=p.tenant_id
        WHERE p.tenant_id=#{tenantId} AND p.is_deleted=0
          AND (#{escapedName}='' OR p.normalized_name LIKE CONCAT('%',#{escapedName},'%') ESCAPE '!')
          AND (#{escapedEmployeeNo}='' OR p.employee_no LIKE CONCAT('%',#{escapedEmployeeNo},'%') ESCAPE '!')
          AND (#{escapedDepartment}='' OR e.organization_name LIKE CONCAT('%',#{escapedDepartment},'%') ESCAPE '!'
               OR e.department_name LIKE CONCAT('%',#{escapedDepartment},'%') ESCAPE '!')
        """)
    long countActivePersons(@Param("tenantId") long tenantId,
                            @Param("escapedName") String escapedName,
                            @Param("escapedEmployeeNo") String escapedEmployeeNo,
                            @Param("escapedDepartment") String escapedDepartment);

    @Select("""
        SELECT p.id AS person_id,p.person_name,p.employee_no,e.organization_name,e.department_name,e.position_name,
               e.professional_title_or_skill,f.id AS member_id,f.member_name,f.relationship_name
        FROM pa_family_member f
        JOIN pa_person p ON p.id=f.person_id AND p.tenant_id=f.tenant_id AND p.is_deleted=0
        LEFT JOIN pa_employment e ON e.person_id=p.id AND e.tenant_id=p.tenant_id
        WHERE f.tenant_id=#{tenantId} AND f.normalized_member_name LIKE CONCAT('%',#{escapedName},'%') ESCAPE '!'
        ORDER BY p.person_name,p.id,f.sort_order
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<FamilySearchRow> searchFamily(@Param("tenantId") long tenantId, @Param("escapedName") String escapedName,
                                      @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
        SELECT COUNT(*) FROM pa_family_member f
        JOIN pa_person p ON p.id=f.person_id AND p.tenant_id=f.tenant_id AND p.is_deleted=0
        WHERE f.tenant_id=#{tenantId} AND f.normalized_member_name LIKE CONCAT('%',#{escapedName},'%') ESCAPE '!'
        """)
    long countFamily(@Param("tenantId") long tenantId, @Param("escapedName") String escapedName);

    record PersonRow(long id, String employeeNo, String personName, byte[] idCardCiphertext, String idCardLastFour,
                     String gender, String birthDate, String nation, String nativePlace,
                     String birthPlace, String politicalStatus, String politicalJoinDate,
                     String maritalStatus, String healthStatus,
                     String mailingAddress, String postalCode, String phoneNumber, String declarationSigner,
                     String declarationDate, String templateVersion,
                     long snapshotVersion, Instant updatedAt) {}
    record EmploymentRow(String organizationName, String departmentName, String workStartDate,
                         String dispatchedToBiddingCompanyDate, String positionName,
                         String professionalTitleOrSkill, String titleOrSkillAcquiredDate) {}
    record EducationRow(String educationType, String institutionName, String departmentAndMajor, String educationLevel,
                        String degreeName, String startDate, String endDate, String rawText, int sortOrder) {}
    record WorkRow(String startDate, String endDate, String organizationAndPosition, String rawText, int sortOrder) {}
    record RewardRow(String occurredDate, String rawText, int sortOrder) {}
    record FamilyRow(long id, String relationshipName, String memberName, String birthDate,
                     String ageText, String politicalStatus, String organizationAndPosition,
                     String rawText, int sortOrder) {}
    record PhotoRow(byte[] photoData, String mimeType, String originalFilename, long byteSize,
                    int widthPixels, int heightPixels, byte[] sha256,
                    int anchorCol1, int anchorRow1, int anchorDx1, int anchorDy1,
                    int anchorCol2, int anchorRow2, int anchorDx2, int anchorDy2, String anchorType) {}
    record PhotoMetadataRow(String mimeType, String originalFilename, long byteSize,
                            int widthPixels, int heightPixels) {}
    record PersonListRow(long id, String employeeNo, String personName, String organizationName,
                         String departmentName, String positionName, String professionalTitleOrSkill,
                         Instant updatedAt) {}
    record FamilySearchRow(long personId, String personName, String employeeNo, String organizationName,
                           String departmentName, String positionName, String professionalTitleOrSkill,
                           long memberId, String memberName, String relationshipName) {}
}
