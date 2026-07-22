package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;

@Service
/**
 * 批量导入协调器：批次本身负责汇总，每个工作簿交给独立事务处理。
 * 单个文件失败必须成为该条目的失败结果，不能回滚同一批次中已经成功的其他文件。
 */
public class ImportBatchService {
    private final ImportMapper mapper;
    private final ImportItemService items;
    private final ArchiveProperties properties;

    public ImportBatchService(ImportMapper mapper, ImportItemService items, ArchiveProperties properties) {
        this.mapper = mapper; this.items = items; this.properties = properties;
    }

    public ImportDtos.BatchResponse precheck(List<MultipartFile> files, long operatorId) {
        if (files == null || files.isEmpty() || files.size() > 100) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 1 至 100 个文件");
        ImportMapper.BatchInsert batch = new ImportMapper.BatchInsert();
        batch.tenantId = properties.tenantId(); batch.totalCount = files.size(); batch.operatorId = operatorId;
        mapper.insertBatch(batch);
        List<ImportDtos.ItemResult> results = new ArrayList<>();
        // 不在本方法开启共享事务；ImportItemService 使用 REQUIRES_NEW 建立逐文件原子边界。
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            byte[] bytes = new byte[0];
            try {
                bytes = read(file);
                results.add(items.precheck(batch.id, index + 1, file.getOriginalFilename(), bytes, operatorId));
            } catch (BusinessException exception) {
                results.add(items.recordFailure(batch.id, index + 1, file == null ? null : file.getOriginalFilename(), bytes,
                    exception.code(), exception.getMessage(), operatorId));
            } catch (DataIntegrityViolationException exception) {
                results.add(items.recordFailure(batch.id, index + 1, file == null ? null : file.getOriginalFilename(), bytes,
                    ErrorCode.CONCURRENT_ARCHIVE_CONFLICT, ErrorCode.CONCURRENT_ARCHIVE_CONFLICT.message(), operatorId));
            } catch (RuntimeException exception) {
                // 未知异常仅返回稳定安全错误码，不把堆栈或工作簿内容暴露给客户端。
                results.add(items.recordFailure(batch.id, index + 1, file == null ? null : file.getOriginalFilename(), bytes,
                    ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(), operatorId));
            }
        }
        mapper.refreshBatch(batch.id);
        return response(batch.id, results);
    }

    public ImportDtos.ItemResult decide(long itemId, String decision, long operatorId) {
        try {
            return items.decide(itemId, decision, operatorId);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.CONCURRENT_ARCHIVE_CONFLICT);
        }
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
