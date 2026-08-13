/* 档案删除应用服务：在单一事务中完成租户隔离的软删除与安全审计。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.common.OperationalLog;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonDeletionService {
    public static final int MAX_REASON_LENGTH = 10_000;
    private static final Logger log = LoggerFactory.getLogger(PersonDeletionService.class);

    private final PersonMapper persons;
    private final AuditService audit;
    private final ArchiveProperties properties;

    public PersonDeletionService(PersonMapper persons, AuditService audit, ArchiveProperties properties) {
        this.persons = persons;
        this.audit = audit;
        this.properties = properties;
    }

    @Transactional
    public void delete(long personId, String reason, long operatorId) {
        long started = OperationalLog.started();
        log.info("event=person_deletion_started operatorId={} personId={}", operatorId, personId);
        try {
            String normalizedReason = reason == null ? "" : reason.strip();
            if (normalizedReason.isEmpty() || normalizedReason.length() > MAX_REASON_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除原因须为 1 至 10000 个字符");
            }
            if (persons.softDeleteActive(properties.tenantId(), personId, operatorId, normalizedReason) != 1) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "档案不存在或已删除");
            }
            // 删除原因属于可能含个人信息的自由文本，只在档案行保存，审计日志仅记录动作元数据。
            audit.record(operatorId, "DELETE", "PERSON", personId, "DELETED", true);
            OperationalLog.afterCommit(() -> log.info(
                "event=person_deletion_completed operatorId={} personId={} durationMs={}",
                operatorId, personId, OperationalLog.elapsedMillis(started)));
        } catch (BusinessException exception) {
            log.warn("event=person_deletion_failed operatorId={} personId={} code={} durationMs={}",
                operatorId, personId, exception.code().name(), OperationalLog.elapsedMillis(started));
            throw exception;
        }
    }
}
