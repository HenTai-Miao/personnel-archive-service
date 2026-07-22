/* 审计持久化映射：只写入动作、目标、结果等元数据，不接收敏感业务载荷。 */
package com.company.personnelarchive.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditMapper {
    @Insert("""
        INSERT INTO sys_operation_log
          (tenant_id,operator_user_id,action_code,resource_type,resource_id,subject_person_id,related_person_id,
           import_batch_id,import_item_id,outcome,error_code,request_id,occurred_at)
        VALUES
          (#{tenantId},#{userId},#{action},#{targetType},#{targetId},#{subjectPersonId},#{relatedPersonId},
           #{batchId},#{itemId},#{outcome},#{errorCode},#{requestId},CURRENT_TIMESTAMP(6))
        """)
    int insert(@Param("tenantId") long tenantId, @Param("userId") Long userId,
               @Param("action") String action, @Param("targetType") String targetType,
               @Param("targetId") Long targetId, @Param("subjectPersonId") Long subjectPersonId,
               @Param("relatedPersonId") Long relatedPersonId, @Param("batchId") Long batchId,
               @Param("itemId") Long itemId, @Param("outcome") String outcome,
               @Param("errorCode") String errorCode, @Param("requestId") String requestId);
}
