/* 导入记录查询服务：从持久化批次条目读取可分页的逐文件处理历史。 */
package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Service;

@Service
public class ImportHistoryService {
    private final ImportMapper mapper;
    private final ArchiveProperties properties;
    private final AuditService audit;

    public ImportHistoryService(ImportMapper mapper, ArchiveProperties properties, AuditService audit) {
        this.mapper = mapper;
        this.properties = properties;
        this.audit = audit;
    }

    public ImportDtos.HistoryPage history(int page, int size, long operatorId) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数不正确");
        }
        long total = mapper.countHistory(properties.tenantId());
        var content = mapper.findHistory(properties.tenantId(), (long) page * size, size).stream()
            .map(row -> new ImportDtos.HistoryItem(row.itemId(), row.batchId(), row.operatorName(),
                ImportFilename.safe(row.fileName()), row.status(), row.code(), row.message(),
                row.processedAt().toString()))
            .toList();
        int totalPages = (int) Math.ceil((double) total / size);
        audit.record(operatorId, "VIEW", "IMPORT_HISTORY", null, "SUCCESS", true);
        return new ImportDtos.HistoryPage(content, page, size, total, totalPages);
    }
}
