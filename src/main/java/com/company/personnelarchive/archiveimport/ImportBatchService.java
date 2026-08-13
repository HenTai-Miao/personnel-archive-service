package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.common.OperationalLog;
import com.company.personnelarchive.common.SafeExceptionSummary;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
/**
 * 批量导入协调器：批次本身负责汇总，每个工作簿交给独立事务处理。
 * 单个文件失败必须成为该条目的失败结果，不能回滚同一批次中已经成功的其他文件。
 */
public class ImportBatchService {
    private static final Logger log = LoggerFactory.getLogger(ImportBatchService.class);
    private final ImportMapper mapper;
    private final ImportItemService items;
    private final ArchiveProperties properties;

    public ImportBatchService(ImportMapper mapper, ImportItemService items, ArchiveProperties properties) {
        this.mapper = mapper; this.items = items; this.properties = properties;
    }

    public ImportDtos.BatchResponse precheck(List<MultipartFile> files, long operatorId) {
        if (files == null || files.isEmpty() || files.size() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 1 至 100 个文件");
        }
        long started = OperationalLog.started();
        ImportMapper.BatchInsert batch = new ImportMapper.BatchInsert();
        try {
            batch.tenantId = properties.tenantId(); batch.totalCount = files.size(); batch.operatorId = operatorId;
            mapper.insertBatch(batch);
            log.info("event=import_batch_started operatorId={} batchId={} fileCount={}",
                operatorId, batch.id, files.size());
            List<ImportDtos.ItemResult> results = new ArrayList<>();
            // 不在本方法开启共享事务；ImportItemService 使用 REQUIRES_NEW 建立逐文件原子边界。
            for (int index = 0; index < files.size(); index++) {
                long itemStarted = OperationalLog.started();
                MultipartFile file = files.get(index);
                byte[] bytes = new byte[0];
                try {
                    bytes = read(file);
                    results.add(items.precheck(batch.id, index + 1, file.getOriginalFilename(), bytes, operatorId));
                } catch (BusinessException exception) {
                    log.warn("event=import_item_failed batchId={} itemOrder={} code={} durationMs={}",
                        batch.id, index + 1, exception.code().name(), OperationalLog.elapsedMillis(itemStarted));
                    results.add(items.recordFailure(batch.id, index + 1, file == null ? null : file.getOriginalFilename(), bytes,
                        exception.code(), exception.getMessage(), operatorId));
                } catch (DataIntegrityViolationException exception) {
                    log.warn("event=import_item_failed batchId={} itemOrder={} code={} durationMs={}",
                        batch.id, index + 1, ErrorCode.CONCURRENT_ARCHIVE_CONFLICT.name(),
                        OperationalLog.elapsedMillis(itemStarted));
                    results.add(items.recordFailure(batch.id, index + 1, file == null ? null : file.getOriginalFilename(), bytes,
                        ErrorCode.CONCURRENT_ARCHIVE_CONFLICT, ErrorCode.CONCURRENT_ARCHIVE_CONFLICT.message(), operatorId));
                } catch (RuntimeException exception) {
                    // 未知异常仅返回稳定安全错误码，不把堆栈或工作簿内容暴露给客户端。
                    log.error("event=import_item_failed batchId={} itemOrder={} code={} durationMs={} {}",
                        batch.id, index + 1, ErrorCode.INTERNAL_ERROR.name(), OperationalLog.elapsedMillis(itemStarted),
                        SafeExceptionSummary.describe(exception));
                    results.add(items.recordFailure(batch.id, index + 1, file == null ? null : file.getOriginalFilename(), bytes,
                        ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(), operatorId));
                }
            }
            mapper.refreshBatch(batch.id);
            ImportDtos.BatchResponse response = response(batch.id, results);
            log.info("event=import_batch_completed operatorId={} batchId={} fileCount={} importedCount={} "
                    + "pendingCount={} failedCount={} skippedCount={} overwrittenCount={} durationMs={}",
                operatorId, batch.id, files.size(), count(results, "IMPORTED"), count(results, "PENDING_DECISION"),
                count(results, "FAILED"), count(results, "SKIPPED"), count(results, "OVERWRITTEN"),
                OperationalLog.elapsedMillis(started));
            return response;
        } catch (BusinessException exception) {
            log.warn("event=import_batch_failed operatorId={} batchId={} fileCount={} code={} durationMs={}",
                operatorId, batchId(batch), files.size(), exception.code().name(), OperationalLog.elapsedMillis(started));
            throw exception;
        } catch (RuntimeException exception) {
            log.error("event=import_batch_failed operatorId={} batchId={} fileCount={} code={} durationMs={} {}",
                operatorId, batchId(batch), files.size(), ErrorCode.INTERNAL_ERROR.name(),
                OperationalLog.elapsedMillis(started), SafeExceptionSummary.describe(exception));
            throw exception;
        }
    }

    public ImportDtos.ItemResult decide(long itemId, String decision, long operatorId) {
        long started = OperationalLog.started();
        try {
            return items.decide(itemId, decision, operatorId);
        } catch (DataIntegrityViolationException exception) {
            log.warn("event=import_decision_failed itemId={} operatorId={} decision={} code={} durationMs={}",
                itemId, operatorId, decisionCode(decision), ErrorCode.CONCURRENT_ARCHIVE_CONFLICT.name(),
                OperationalLog.elapsedMillis(started));
            throw new BusinessException(ErrorCode.CONCURRENT_ARCHIVE_CONFLICT);
        }
    }

    private static long count(List<ImportDtos.ItemResult> results, String status) {
        return results.stream().filter(item -> status.equals(item.status())).count();
    }

    private static String batchId(ImportMapper.BatchInsert batch) {
        return batch.id == null ? "none" : Long.toString(batch.id);
    }

    private static String decisionCode(String decision) {
        return "OVERWRITE".equals(decision) || "SKIP".equals(decision) ? decision : "INVALID";
    }

    private ImportDtos.BatchResponse response(long batchId, List<ImportDtos.ItemResult> results) {
        ImportMapper.BatchRow row = mapper.findBatch(properties.tenantId(), batchId);
        return new ImportDtos.BatchResponse(row.id(), List.copyOf(results));
    }

    private byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件不能为空");
        if (file.getSize() > properties.excel().maxFileBytes()) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        try { return file.getBytes(); }
        catch (IOException exception) { throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "无法读取上传文件"); }
    }
}
