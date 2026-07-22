/* 批量档案导出：逐份重建 XLSX、无覆盖写入一个有界 ZIP，并在整包成功后记录审计。 */
package com.company.personnelarchive.archiveexport;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class BatchArchiveExportService {
    private static final int MAX_ITEMS = 100;
    private static final long MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;
    private static final Pattern PERSON_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final ArchiveExportService workbookExports;
    private final AuditService audit;
    private final Clock clock;

    public BatchArchiveExportService(ArchiveExportService workbookExports, AuditService audit, Clock clock) {
        this.workbookExports = workbookExports;
        this.audit = audit;
        this.clock = clock;
    }

    public GeneratedBundle generate(List<String> externalIds, long operatorId) {
        List<Long> personIds = parseIds(externalIds);
        byte[] bytes = buildZip(personIds);
        // 生成或打包任一项失败时不会执行到这里，避免把未交付的档案记成成功导出。
        personIds.forEach(personId -> audit.record(
            operatorId, "EXPORT", "PERSON", personId, "SUCCESS", true));
        String timestamp = FILE_TIMESTAMP.withZone(clock.getZone()).format(clock.instant());
        return new GeneratedBundle("人员档案批量导出-" + timestamp + ".zip", bytes);
    }

    private byte[] buildZip(List<Long> personIds) {
        var output = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        long uncompressedBytes = 0;
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            // XLSX 本身已经是 ZIP，关闭二次压缩可显著降低批量导出的 CPU 开销。
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (long personId : personIds) {
                ArchiveExportService.GeneratedArchive archive = workbookExports.generateForBatch(personId);
                if (archive.bytes().length > MAX_UNCOMPRESSED_BYTES - uncompressedBytes) {
                    throw new BusinessException(ErrorCode.BATCH_EXPORT_TOO_LARGE);
                }
                uncompressedBytes += archive.bytes().length;
                ZipEntry entry = new ZipEntry(uniqueName(archive.filename(), usedNames));
                entry.setTime(clock.millis());
                zip.putNextEntry(entry);
                zip.write(archive.bytes());
                zip.closeEntry();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "批量生成档案压缩包失败");
        }
        return output.toByteArray();
    }

    private static List<Long> parseIds(List<String> externalIds) {
        if (externalIds == null || externalIds.isEmpty() || externalIds.size() > MAX_ITEMS) {
            throw invalidSelection();
        }
        List<Long> parsed = new ArrayList<>(externalIds.size());
        Set<Long> unique = new HashSet<>();
        for (String externalId : externalIds) {
            if (externalId == null || !PERSON_ID.matcher(externalId).matches()) throw invalidSelection();
            try {
                long personId = Long.parseLong(externalId);
                if (!unique.add(personId)) throw invalidSelection();
                parsed.add(personId);
            } catch (NumberFormatException exception) {
                throw invalidSelection();
            }
        }
        return List.copyOf(parsed);
    }

    private static String uniqueName(String original, Set<String> usedNames) {
        String safe = original.replaceAll("[\\\\/\\r\\n]", "_");
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".xlsx")) safe = "人员档案.xlsx";
        if (usedNames.add(safe.toLowerCase(Locale.ROOT))) return safe;
        String stem = safe.substring(0, safe.length() - ".xlsx".length());
        for (int sequence = 1; sequence <= MAX_ITEMS; sequence++) {
            String candidate = stem + " (" + sequence + ").xlsx";
            if (usedNames.add(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法生成不重复的档案文件名");
    }

    private static BusinessException invalidSelection() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 1 至 100 份不同的人员档案");
    }

    public record GeneratedBundle(String filename, byte[] bytes) {}
}
