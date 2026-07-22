package com.company.personnelarchive.archiveimport;

import org.springframework.stereotype.Component;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
/**
 * 重复档案等待用户决定时的有界、短期内存存储。
 * 这里只保存解析后的领域文档，不保存上传源 XLSX；过期或服务重启后客户端必须重新预检。
 */
public class PendingImportStore {
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxItems;

    public PendingImportStore(Clock clock, ArchiveProperties properties) {
        this.clock = clock;
        this.maxItems = properties.excel().pendingMaxItems();
    }

    public void put(long itemId, ArchiveDocument document, long matchedPersonId, long matchedSnapshotVersion, Instant expiresAt) {
        // 先清过期条目再检查硬上限，防止恶意批次无限占用包含照片的内存。
        evictExpired();
        if (!entries.containsKey(itemId) && entries.size() >= maxItems) {
            throw new BusinessException(ErrorCode.PENDING_IMPORT_CAPACITY_EXCEEDED);
        }
        entries.put(itemId, new Entry(document, matchedPersonId, matchedSnapshotVersion, expiresAt));
    }

    public Optional<Entry> get(long itemId) {
        Entry entry = entries.get(itemId);
        if (entry == null) return Optional.empty();
        if (!entry.expiresAt().isAfter(clock.instant())) {
            // 读取时再次惰性过期，确保没有后台清理线程时也不会接受陈旧决定。
            entries.remove(itemId, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void discard(long itemId) { entries.remove(itemId); }

    private void evictExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    public record Entry(ArchiveDocument document, long matchedPersonId, long matchedSnapshotVersion, Instant expiresAt) {}
}
