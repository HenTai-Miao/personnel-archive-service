/* 审计应用服务：为查看、导入、覆盖、登录和导出生成统一的安全审计记录。 */
package com.company.personnelarchive.audit;

import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AuditService {
    private static final Set<String> ACTIONS = Set.of(
        "LOGIN", "LOGIN_FAILED", "LOGOUT", "IMPORT", "OVERWRITE", "SKIP", "EDIT", "VIEW", "EXPORT", "ADMINISTRATION"
    );
    private final AuditMapper mapper;
    private final ArchiveProperties properties;

    public AuditService(AuditMapper mapper, ArchiveProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public void record(Long userId, String action, String targetType, Long targetId, String resultCode, boolean success) {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported audit action");
        mapper.insert(properties.tenantId(), userId, action, safe(targetType), targetId,
            "PERSON".equals(targetType) ? targetId : null, null, null, null,
            success ? "SUCCESS" : "FAILURE", success ? null : safe(resultCode), java.util.UUID.randomUUID().toString());
    }

    public void recordImport(Long userId, String action, Long personId, long batchId, long itemId,
                             String resultCode, boolean success) {
        mapper.insert(properties.tenantId(), userId, action, "IMPORT_ITEM", itemId, personId, null, batchId, itemId,
            success ? "SUCCESS" : "FAILURE", success ? null : safe(resultCode), java.util.UUID.randomUUID().toString());
    }

    public void recordOverwrite(long userId, long oldPersonId, long newPersonId, long batchId, long itemId) {
        mapper.insert(properties.tenantId(), userId, "OVERWRITE", "PERSON", oldPersonId, oldPersonId, newPersonId,
            batchId, itemId, "SUCCESS", null, java.util.UUID.randomUUID().toString());
    }

    public void recordSensitiveView(long userId, long personId, String fieldCode) {
        // 只记录所查看的字段类型，不记录身份证或手机号本身。
        mapper.insert(properties.tenantId(), userId, "VIEW", "PERSON_SENSITIVE_" + safe(fieldCode), personId,
            personId, null, null, null, "SUCCESS", null, java.util.UUID.randomUUID().toString());
    }

    static String safe(String value) {
        if (value == null) return null;
        return value.replaceAll("[\\r\\n\\t]", "_").substring(0, Math.min(value.length(), 64));
    }
}
