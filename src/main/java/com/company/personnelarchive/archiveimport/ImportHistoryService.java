/* 导入记录查询服务：从持久化批次条目读取可分页的逐文件处理历史。 */
package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.common.OperationalLog;
import com.company.personnelarchive.common.SafeExceptionSummary;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ImportHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ImportHistoryService.class);
    private final ImportMapper mapper;
    private final ArchiveProperties properties;
    private final AuditService audit;

    public ImportHistoryService(ImportMapper mapper, ArchiveProperties properties, AuditService audit) {
        this.mapper = mapper;
        this.properties = properties;
        this.audit = audit;
    }

    public ImportDtos.HistoryPage history(int page, int size, long operatorId) {
        long started = OperationalLog.started();
        log.info("event=import_history_started operatorId={} page={} size={}", operatorId, page, size);
        try {
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
            log.info("event=import_history_completed operatorId={} page={} size={} returnedCount={} total={} durationMs={}",
                operatorId, page, size, content.size(), total, OperationalLog.elapsedMillis(started));
            return new ImportDtos.HistoryPage(content, page, size, total, totalPages);
        } catch (BusinessException exception) {
            log.warn("event=import_history_failed operatorId={} page={} size={} code={} durationMs={}",
                operatorId, page, size, exception.code().name(), OperationalLog.elapsedMillis(started));
            throw exception;
        } catch (RuntimeException exception) {
            log.error("event=import_history_failed operatorId={} page={} size={} code={} durationMs={} {}",
                operatorId, page, size, ErrorCode.INTERNAL_ERROR.name(), OperationalLog.elapsedMillis(started),
                SafeExceptionSummary.describe(exception));
            throw exception;
        }
    }
}
