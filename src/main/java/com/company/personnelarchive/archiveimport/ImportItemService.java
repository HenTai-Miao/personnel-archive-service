package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.person.NameNormalizer;
import com.company.personnelarchive.person.PersonMapper;
import com.company.personnelarchive.person.SnapshotWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;

@Service
/**
 * 单个工作簿的导入事务服务。
 *
 * <p>新档案直接写入完整快照；重复档案先进入待决定状态。覆盖时重新加锁和复核版本，
 * 再原子地软删除旧根、插入新快照并链接历史，绝不原地修改旧快照。</p>
 */
public class ImportItemService {
    private final ImportMapper importMapper;
    private final PersonMapper personMapper;
    private final SnapshotWriter writer;
    private final ExcelArchiveReader excelReader;
    private final ArchiveProperties properties;
    private final AuditService audit;
    private final Clock clock;
    private final PendingImportStore pendingStore;

    public ImportItemService(ImportMapper importMapper, PersonMapper personMapper, SnapshotWriter writer,
                             ExcelArchiveReader excelReader, ArchiveProperties properties, AuditService audit, Clock clock,
                             PendingImportStore pendingStore) {
        this.importMapper = importMapper; this.personMapper = personMapper; this.writer = writer;
        this.excelReader = excelReader; this.properties = properties; this.audit = audit; this.clock = clock;
        this.pendingStore = pendingStore;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportDtos.ItemResult precheck(long batchId, int order, String filename, byte[] file, long operatorId) {
        // 每个文件使用新事务，使批量中的解析、约束或数据库失败互不影响。
        byte[] fileHash = sha256(file);
        ArchiveDocument document = excelReader.read(new ByteArrayInputStream(file), file.length, filename);
        SnapshotWriter.PreparedArchive prepared = writer.prepare(document);
        PersonMapper.ActivePerson identityMatch = prepared.identityHmac() == null ? null
            : personMapper.findActiveByIdentity(properties.tenantId(), prepared.identityHmac());
        if (identityMatch != null) {
            // 仅当姓名已填写且规范化姓名与身份证同时相同，才进入覆盖/跳过选择。
            if (prepared.normalizedName().isBlank()
                || !prepared.normalizedName().equals(identityMatch.normalizedName())) {
                throw new BusinessException(ErrorCode.IDENTITY_CARD_CONFLICT);
            }
            PersonMapper.ActivePerson employeeMatch = findEmployeeMatch(prepared.employeeNo());
            if (employeeMatch != null && employeeMatch.id() != identityMatch.id()) throw new BusinessException(ErrorCode.EMPLOYEE_NO_CONFLICT);
            return pending(batchId, order, filename, file, fileHash, document, prepared.employeeNo(), identityMatch,
                operatorId);
        }
        PersonMapper.ActivePerson employeeMatch = findEmployeeMatch(prepared.employeeNo());
        if (employeeMatch != null) throw new BusinessException(ErrorCode.EMPLOYEE_NO_CONFLICT);
        long personId = writer.insert(prepared, operatorId, 1, null);
        ImportMapper.ItemInsert item = base(batchId, order, filename, file, fileHash);
        item.status = "IMPORTED"; item.resultCode = "IMPORTED"; item.message = "导入成功"; item.importedPersonId = personId;
        importMapper.insertItem(item);
        audit.recordImport(operatorId, "IMPORT", personId, batchId, item.id, "IMPORTED", true);
        return new ImportDtos.ItemResult(item.id, filename, item.status, item.resultCode, item.message, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportDtos.ItemResult recordFailure(long batchId, int order, String filename, byte[] file,
                                                ErrorCode code, String safeMessage, long operatorId) {
        ImportMapper.ItemInsert item = base(batchId, order, filename, file, sha256(file));
        item.status = "FAILED"; item.resultCode = code.name(); item.message = safeMessage;
        importMapper.insertItem(item);
        audit.recordImport(operatorId, "IMPORT", null, batchId, item.id, code.name(), false);
        return new ImportDtos.ItemResult(item.id, filename, item.status, item.resultCode, item.message, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportDtos.ItemResult decide(long itemId, String decision, long operatorId) {
        if (!"OVERWRITE".equals(decision) && !"SKIP".equals(decision)) throw new BusinessException(ErrorCode.INVALID_IMPORT_DECISION);
        // 锁住导入条目，防止两个客户端同时对同一个待决定项执行不同操作。
        ImportMapper.DecisionRow item = importMapper.lockDecision(properties.tenantId(), itemId);
        if (item == null || !"PENDING_DECISION".equals(item.itemStatus()) || !item.decisionExpiresAt().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.STALE_IMPORT_DECISION);
        }
        if ("SKIP".equals(decision)) {
            // SKIP 只完成导入审计条目，不触碰任何人员档案表。
            discardAfterCommit(itemId);
            importMapper.completeDecision(itemId, "SKIPPED", "SKIPPED", "已跳过，档案未变更", null, decision, operatorId);
            audit.recordImport(operatorId, "SKIP", item.matchedPersonId(), item.batchId(), item.id(), "SKIPPED", true);
            importMapper.refreshBatch(item.batchId());
            return new ImportDtos.ItemResult(itemId, item.originalFilename(), "SKIPPED", "SKIPPED", "已跳过，档案未变更", null);
        }
        // 待决定存储只有解析后的领域文档，没有原始 XLSX；进程重启或过期后要求重新预检。
        PendingImportStore.Entry pending = pendingStore.get(itemId).orElseThrow(() -> new BusinessException(ErrorCode.STALE_IMPORT_DECISION));
        SnapshotWriter.PreparedArchive prepared = writer.prepare(pending.document());
        // 决定可能晚于预检几十分钟，因此必须锁定当前有效快照并重新校验版本、姓名和身份。
        PersonMapper.ActivePerson active = personMapper.lockActiveById(properties.tenantId(), item.matchedPersonId());
        if (active == null || active.snapshotVersion() != item.matchedSnapshotVersion()
            || pending.matchedPersonId() != item.matchedPersonId() || pending.matchedSnapshotVersion() != item.matchedSnapshotVersion()
            || !active.normalizedName().equals(prepared.normalizedName())
            || !Arrays.equals(active.idCardHmac(), prepared.identityHmac())) {
            throw new BusinessException(ErrorCode.STALE_IMPORT_DECISION);
        }
        PersonMapper.ActivePerson employeeMatch = findEmployeeMatch(prepared.employeeNo());
        if (employeeMatch != null && employeeMatch.id() != active.id()) throw new BusinessException(ErrorCode.EMPLOYEE_NO_CONFLICT);
        // 软删除、新快照、历史链接同属当前事务，任一步失败都会整体回滚。
        if (personMapper.softDeleteForOverwrite(active.id(), active.snapshotVersion(), operatorId) != 1) {
            throw new BusinessException(ErrorCode.STALE_IMPORT_DECISION);
        }
        long newPersonId = writer.insert(prepared, operatorId, active.snapshotVersion() + 1, active.id());
        if (personMapper.linkReplacement(active.id(), newPersonId, operatorId) != 1) {
            throw new BusinessException(ErrorCode.STALE_IMPORT_DECISION);
        }
        importMapper.completeDecision(itemId, "OVERWRITTEN", "OVERWRITTEN", "已创建新快照并保留历史版本", newPersonId, decision, operatorId);
        discardAfterCommit(itemId);
        audit.recordOverwrite(operatorId, active.id(), newPersonId, item.batchId(), item.id());
        importMapper.refreshBatch(item.batchId());
        return new ImportDtos.ItemResult(itemId, item.originalFilename(), "OVERWRITTEN", "OVERWRITTEN",
            "已创建新快照并保留历史版本", null);
    }

    private ImportDtos.ItemResult pending(long batchId, int order, String filename, byte[] file, byte[] fileHash,
                                          ArchiveDocument document, String incomingEmployeeNo, PersonMapper.ActivePerson match,
                                          long operatorId) {
        Instant expiresAt = clock.instant().plusSeconds(30 * 60);
        ImportMapper.ItemInsert item = base(batchId, order, filename, file, fileHash);
        item.status = "PENDING_DECISION"; item.resultCode = ErrorCode.DUPLICATE_REQUIRES_DECISION.name();
        item.message = ErrorCode.DUPLICATE_REQUIRES_DECISION.message(); item.matchedPersonId = match.id();
        item.matchedSnapshotVersion = match.snapshotVersion();
        item.decisionExpiresAt = expiresAt;
        importMapper.insertItem(item);
        // 数据库只保存大小、哈希和决定元数据；可覆盖内容在有界内存中过期，不永久保留源文件。
        pendingStore.put(item.id, document, match.id(), match.snapshotVersion(), expiresAt);
        audit.recordImport(operatorId, "IMPORT", match.id(), batchId, item.id,
            ErrorCode.DUPLICATE_REQUIRES_DECISION.name(), true);
        var duplicate = new ImportDtos.DuplicateSummary(match.personName(), match.idCardLastFour(), match.employeeNo(),
            incomingEmployeeNo, match.updatedAt().toString());
        return new ImportDtos.ItemResult(item.id, filename, item.status, item.resultCode, item.message, duplicate);
    }

    private ImportMapper.ItemInsert base(long batchId, int order, String filename, byte[] file, byte[] hash) {
        ImportMapper.ItemInsert item = new ImportMapper.ItemInsert();
        item.tenantId = properties.tenantId(); item.batchId = batchId; item.itemOrder = order;
        item.filename = filename == null ? "unnamed.xlsx" : filename.substring(0, Math.min(filename.length(), 255));
        item.fileSize = file.length; item.fileSha256 = hash; item.templateVersion = ExcelTemplateV1.VERSION;
        return item;
    }

    private PersonMapper.ActivePerson findEmployeeMatch(String employeeNo) {
        return employeeNo.isBlank() ? null
            : personMapper.findActiveByEmployeeNo(properties.tenantId(), employeeNo);
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void discardAfterCommit(long itemId) {
        // 必须等数据库提交成功再丢弃内存文档，否则提交失败后用户无法重试原决定。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { pendingStore.discard(itemId); }
                });
        } else {
            pendingStore.discard(itemId);
        }
    }
}
