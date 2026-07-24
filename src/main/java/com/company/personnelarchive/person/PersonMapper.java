/* 人员写模型映射：查找/锁定有效快照，并写入不可变根、子表、照片与历史链接。 */
package com.company.personnelarchive.person;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface PersonMapper {
    @Select("SELECT id,employee_no,person_name,normalized_name,id_card_hmac,id_card_last_four,snapshot_version,updated_at FROM pa_person WHERE tenant_id=#{tenantId} AND id_card_hmac=#{hmac} AND is_deleted=0")
    ActivePerson findActiveByIdentity(@Param("tenantId") long tenantId, @Param("hmac") byte[] hmac);

    @Select("SELECT id,employee_no,person_name,normalized_name,id_card_hmac,id_card_last_four,snapshot_version,updated_at FROM pa_person WHERE tenant_id=#{tenantId} AND employee_no=#{employeeNo} AND is_deleted=0")
    ActivePerson findActiveByEmployeeNo(@Param("tenantId") long tenantId, @Param("employeeNo") String employeeNo);

    @Select("SELECT id,employee_no,person_name,normalized_name,id_card_hmac,id_card_last_four,snapshot_version,updated_at FROM pa_person WHERE tenant_id=#{tenantId} AND id=#{personId} AND is_deleted=0 FOR UPDATE")
    ActivePerson lockActiveById(@Param("tenantId") long tenantId, @Param("personId") long personId);

    @Insert("""
        INSERT INTO pa_person
        (tenant_id,employee_no,person_name,normalized_name,id_card_ciphertext,id_card_hmac,id_card_last_four,
         gender,birth_date,nation,native_place,birth_place,political_status,political_join_date,marital_status,
         health_status,mailing_address,postal_code,phone_number,declaration_signer,declaration_date,template_version,
         snapshot_version,previous_person_id,created_by,updated_by)
        VALUES
        (#{tenantId},#{employeeNo},#{personName},#{normalizedName},#{idCardCiphertext},#{idCardHmac},#{idCardLastFour},
         #{gender},#{birthDate},#{nation},#{nativePlace},#{birthPlace},#{politicalStatus},#{politicalJoinDate},#{maritalStatus},
         #{healthStatus},#{mailingAddress},#{postalCode},#{phoneNumber},#{declarationSigner},#{declarationDate},#{templateVersion},
         #{snapshotVersion},#{previousPersonId},#{operatorId},#{operatorId})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPerson(PersonInsert row);

    @Update("UPDATE pa_person SET is_deleted=1,deleted_at=CURRENT_TIMESTAMP(6),deleted_by=#{operatorId},delete_reason='IMPORT_OVERWRITE',updated_by=#{operatorId} WHERE id=#{personId} AND is_deleted=0 AND snapshot_version=#{snapshotVersion}")
    int softDeleteForOverwrite(@Param("personId") long personId, @Param("snapshotVersion") long snapshotVersion,
                               @Param("operatorId") long operatorId);

    @Update("""
        UPDATE pa_person
        SET is_deleted=1,deleted_at=CURRENT_TIMESTAMP(6),deleted_by=#{operatorId},
            delete_reason=#{reason},updated_by=#{operatorId}
        WHERE tenant_id=#{tenantId} AND id=#{personId} AND is_deleted=0
        """)
    int softDeleteActive(@Param("tenantId") long tenantId, @Param("personId") long personId,
                         @Param("operatorId") long operatorId, @Param("reason") String reason);

    @Update("UPDATE pa_person SET replaced_by_person_id=#{newPersonId},updated_by=#{operatorId} WHERE id=#{oldPersonId} AND is_deleted=1")
    int linkReplacement(@Param("oldPersonId") long oldPersonId, @Param("newPersonId") long newPersonId,
                        @Param("operatorId") long operatorId);

    @Insert("INSERT INTO pa_employment (tenant_id,person_id,organization_name,department_name,work_start_date,dispatched_to_bidding_company_date,position_name,professional_title_or_skill,title_or_skill_acquired_date) VALUES (#{tenantId},#{personId},#{organization},#{department},#{workStartDate},#{dispatchDate},#{position},#{titleSkill},#{titleDate})")
    int insertEmployment(@Param("tenantId") long tenantId, @Param("personId") long personId,
                         @Param("organization") String organization, @Param("department") String department,
                         @Param("workStartDate") LocalDate workStartDate, @Param("dispatchDate") LocalDate dispatchDate,
                         @Param("position") String position, @Param("titleSkill") String titleSkill,
                         @Param("titleDate") LocalDate titleDate);

    @Insert("INSERT INTO pa_education (tenant_id,person_id,education_type,institution_name,department_and_major,education_level,degree_name,start_date,end_date,raw_text,sort_order) VALUES (#{tenantId},#{personId},#{type},#{institution},#{major},#{level},#{degree},#{startDate},#{endDate},#{rawText},#{sortOrder})")
    int insertEducation(@Param("tenantId") long tenantId, @Param("personId") long personId, @Param("type") String type,
                        @Param("institution") String institution, @Param("major") String major,
                        @Param("level") String level, @Param("degree") String degree,
                        @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
                        @Param("rawText") String rawText, @Param("sortOrder") int sortOrder);

    @Insert("INSERT INTO pa_work_experience (tenant_id,person_id,start_date,end_date,organization_and_position,raw_text,sort_order) VALUES (#{tenantId},#{personId},#{startDate},#{endDate},#{organizationAndPosition},#{rawText},#{sortOrder})")
    int insertWork(@Param("tenantId") long tenantId, @Param("personId") long personId,
                   @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
                   @Param("organizationAndPosition") String organizationAndPosition,
                   @Param("rawText") String rawText, @Param("sortOrder") int sortOrder);

    @Insert("INSERT INTO pa_reward_punishment (tenant_id,person_id,occurred_date,raw_text,sort_order) VALUES (#{tenantId},#{personId},#{occurredDate},#{rawText},#{sortOrder})")
    int insertReward(@Param("tenantId") long tenantId, @Param("personId") long personId,
                     @Param("occurredDate") LocalDate occurredDate,
                     @Param("rawText") String rawText, @Param("sortOrder") int sortOrder);

    @Insert("INSERT INTO pa_family_member (tenant_id,person_id,relationship_name,member_name,normalized_member_name,birth_date,political_status,organization_and_position,raw_text,sort_order) VALUES (#{tenantId},#{personId},#{relationship},#{name},#{normalizedName},#{birthDate},#{politicalStatus},#{workplace},#{rawText},#{sortOrder})")
    int insertFamily(@Param("tenantId") long tenantId, @Param("personId") long personId,
                     @Param("relationship") String relationship, @Param("name") String name,
                     @Param("normalizedName") String normalizedName, @Param("birthDate") LocalDate birthDate,
                     @Param("politicalStatus") String politicalStatus, @Param("workplace") String workplace,
                     @Param("rawText") String rawText, @Param("sortOrder") int sortOrder);

    @Insert("INSERT INTO pa_person_photo (tenant_id,person_id,photo_data,mime_type,original_filename,byte_size,width_pixels,height_pixels,sha256,anchor_col1,anchor_row1,anchor_dx1,anchor_dy1,anchor_col2,anchor_row2,anchor_dx2,anchor_dy2,anchor_type) VALUES (#{tenantId},#{personId},#{data},#{mime},#{filename},#{size},#{width},#{height},#{sha256},#{anchorCol1},#{anchorRow1},#{anchorDx1},#{anchorDy1},#{anchorCol2},#{anchorRow2},#{anchorDx2},#{anchorDy2},#{anchorType})")
    int insertPhoto(@Param("tenantId") long tenantId, @Param("personId") long personId,
                    @Param("data") byte[] data, @Param("mime") String mime, @Param("filename") String filename,
                    @Param("size") long size, @Param("width") int width, @Param("height") int height,
                    @Param("sha256") byte[] sha256,
                    @Param("anchorCol1") int anchorCol1, @Param("anchorRow1") int anchorRow1,
                    @Param("anchorDx1") int anchorDx1, @Param("anchorDy1") int anchorDy1,
                    @Param("anchorCol2") int anchorCol2, @Param("anchorRow2") int anchorRow2,
                    @Param("anchorDx2") int anchorDx2, @Param("anchorDy2") int anchorDy2,
                    @Param("anchorType") String anchorType);

    record ActivePerson(long id, String employeeNo, String personName, String normalizedName,
                        byte[] idCardHmac, String idCardLastFour, long snapshotVersion, java.time.Instant updatedAt) {}

    class PersonInsert {
        public Long id;
        public long tenantId;
        public String employeeNo;
        public String personName;
        public String normalizedName;
        public byte[] idCardCiphertext;
        public byte[] idCardHmac;
        public String idCardLastFour;
        public String gender;
        public LocalDate birthDate;
        public String nation;
        public String nativePlace;
        public String birthPlace;
        public String politicalStatus;
        public LocalDate politicalJoinDate;
        public String maritalStatus;
        public String healthStatus;
        public String mailingAddress;
        public String postalCode;
        public String phoneNumber;
        public String declarationSigner;
        public LocalDate declarationDate;
        public String templateVersion;
        public long snapshotVersion;
        public Long previousPersonId;
        public long operatorId;
    }
}
