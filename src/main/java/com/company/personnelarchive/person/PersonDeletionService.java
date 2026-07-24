/* 档案删除应用服务：在单一事务中完成租户隔离的软删除与安全审计。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonDeletionService {
    public static final int MAX_REASON_LENGTH = 10_000;

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
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.isEmpty() || normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除原因须为 1 至 10000 个字符");
        }
        if (persons.softDeleteActive(properties.tenantId(), personId, operatorId, normalizedReason) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "档案不存在或已删除");
        }
        // 删除原因属于可能含个人信息的自由文本，只在档案行保存，审计日志仅记录动作元数据。
        audit.record(operatorId, "DELETE", "PERSON", personId, "DELETED", true);
    }
}
