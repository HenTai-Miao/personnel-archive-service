/* 导入持久化映射：管理批次、条目、决定加锁、状态完成和汇总计数 SQL。 */
package com.company.personnelarchive.archiveimport;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ImportMapper {
    @Insert("INSERT INTO pa_import_batch (tenant_id,batch_status,total_count,created_by) VALUES (#{tenantId},'PROCESSING',#{totalCount},#{operatorId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBatch(BatchInsert row);

    @Update("""
        UPDATE pa_import_batch b SET
          pending_count=(SELECT COUNT(*) FROM pa_import_item i WHERE i.batch_id=b.id AND i.item_status='PENDING_DECISION'),
          imported_count=(SELECT COUNT(*) FROM pa_import_item i WHERE i.batch_id=b.id AND i.item_status='IMPORTED'),
          overwritten_count=(SELECT COUNT(*) FROM pa_import_item i WHERE i.batch_id=b.id AND i.item_status='OVERWRITTEN'),
          skipped_count=(SELECT COUNT(*) FROM pa_import_item i WHERE i.batch_id=b.id AND i.item_status='SKIPPED'),
          failed_count=(SELECT COUNT(*) FROM pa_import_item i WHERE i.batch_id=b.id AND i.item_status='FAILED'),
          batch_status=CASE
            WHEN EXISTS(SELECT 1 FROM pa_import_item i WHERE i.batch_id=b.id AND i.item_status='PENDING_DECISION') THEN 'AWAITING_DECISION'
            WHEN EXISTS(SELECT 1 FROM pa_import_item i WHERE i.batch_id=b.id AND i.item_status='FAILED') THEN 'COMPLETED_WITH_ERRORS'
            ELSE 'COMPLETED' END,
          completed_at=CASE WHEN EXISTS(SELECT 1 FROM pa_import_item i WHERE i.batch_id=b.id AND i.item_status='PENDING_DECISION') THEN NULL ELSE CURRENT_TIMESTAMP(6) END,
          updated_at=CURRENT_TIMESTAMP(6)
        WHERE b.id=#{batchId}
        """)
    int refreshBatch(long batchId);

    @Select("SELECT id,batch_status,total_count,pending_count,imported_count,overwritten_count,skipped_count,failed_count,started_at,completed_at FROM pa_import_batch WHERE tenant_id=#{tenantId} AND id=#{batchId}")
    BatchRow findBatch(@Param("tenantId") long tenantId, @Param("batchId") long batchId);

    @Select("SELECT COUNT(*) FROM pa_import_item WHERE tenant_id=#{tenantId}")
    long countHistory(@Param("tenantId") long tenantId);

    @Select("""
        SELECT i.id AS item_id, i.batch_id, COALESCE(decider.display_name, creator.display_name) AS operator_name,
               i.original_filename AS file_name, i.item_status AS status, i.result_code AS code,
               i.result_message AS message, COALESCE(i.processed_at,i.created_at) AS processed_at
        FROM pa_import_item i
        INNER JOIN pa_import_batch b ON b.id=i.batch_id AND b.tenant_id=i.tenant_id
        INNER JOIN sys_user creator ON creator.id=b.created_by AND creator.tenant_id=b.tenant_id
        LEFT JOIN sys_user decider ON decider.id=i.decided_by AND decider.tenant_id=i.tenant_id
        WHERE i.tenant_id=#{tenantId}
        ORDER BY COALESCE(i.processed_at,i.created_at) DESC, i.id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<HistoryRow> findHistory(@Param("tenantId") long tenantId, @Param("offset") long offset,
                                 @Param("limit") int limit);

    @Insert("""
        INSERT INTO pa_import_item
        (tenant_id,batch_id,item_order,original_filename,file_size,file_sha256,template_version,item_status,result_code,
         result_message,matched_person_id,matched_snapshot_version,imported_person_id,decision_expires_at,processed_at)
        VALUES
        (#{tenantId},#{batchId},#{itemOrder},#{filename},#{fileSize},#{fileSha256},#{templateVersion},#{status},#{resultCode},
         #{message},#{matchedPersonId},#{matchedSnapshotVersion},#{importedPersonId},#{decisionExpiresAt},CURRENT_TIMESTAMP(6))
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertItem(ItemInsert row);

    @Select("""
        SELECT id,tenant_id,batch_id,original_filename,file_size,file_sha256,item_status,matched_person_id,
               matched_snapshot_version,decision_expires_at
        FROM pa_import_item
        WHERE tenant_id=#{tenantId} AND id=#{itemId}
        FOR UPDATE
        """)
    DecisionRow lockDecision(@Param("tenantId") long tenantId, @Param("itemId") long itemId);

    @Update("""
        UPDATE pa_import_item SET item_status=#{status},result_code=#{resultCode},result_message=#{message},
          imported_person_id=#{personId},decision=#{decision},decided_at=CURRENT_TIMESTAMP(6),decided_by=#{operatorId},
          decision_expires_at=NULL,processed_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=#{itemId} AND item_status='PENDING_DECISION'
        """)
    int completeDecision(@Param("itemId") long itemId, @Param("status") String status,
                         @Param("resultCode") String resultCode, @Param("message") String message,
                         @Param("personId") Long personId, @Param("decision") String decision,
                         @Param("operatorId") long operatorId);

    class BatchInsert { public Long id; public long tenantId; public int totalCount; public long operatorId; }
    class ItemInsert {
        public Long id; public long tenantId; public long batchId; public int itemOrder; public String filename;
        public long fileSize; public byte[] fileSha256; public String templateVersion; public String status;
        public String resultCode; public String message; public Long matchedPersonId; public Long matchedSnapshotVersion;
        public Long importedPersonId; public Instant decisionExpiresAt;
    }
    record DecisionRow(long id, long tenantId, long batchId, String originalFilename, long fileSize, byte[] fileSha256,
                       String itemStatus, Long matchedPersonId, Long matchedSnapshotVersion,
                       Instant decisionExpiresAt) {}
    record BatchRow(long id, String batchStatus, int totalCount, int pendingCount, int importedCount,
                    int overwrittenCount, int skippedCount, int failedCount, Instant startedAt, Instant completedAt) {}
    record HistoryRow(long itemId, long batchId, String operatorName, String fileName, String status,
                      String code, String message, Instant processedAt) {}
}
