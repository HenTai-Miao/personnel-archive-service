/* 人员 HTTP 边界：提供有效档案列表、详情、家庭搜索和授权导出响应。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.archiveexport.ArchiveExportService;
import com.company.personnelarchive.archiveexport.ArchiveExportDtos;
import com.company.personnelarchive.archiveexport.BatchArchiveExportService;
import com.company.personnelarchive.auth.AuthPrincipal;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@Validated
@RequestMapping("/api/v1")
public class PersonController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    private final PersonQueryService queries;
    private final ArchiveExportService exports;
    private final BatchArchiveExportService batchExports;

    public PersonController(PersonQueryService queries, ArchiveExportService exports,
                            BatchArchiveExportService batchExports) {
        this.queries = queries; this.exports = exports; this.batchExports = batchExports;
    }

    @GetMapping("/persons")
    public PersonDtos.PersonListPage list(
        @RequestParam(defaultValue = "") @Size(max = 128) String name,
        @RequestParam(defaultValue = "") @Size(max = 64) String employeeNo,
        @RequestParam(defaultValue = "") @Size(max = 255) String department,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @AuthenticationPrincipal AuthPrincipal principal) {
        return queries.list(name, employeeNo, department, page, size, principal.userId());
    }

    @GetMapping("/persons/{personId}")
    public PersonDtos.Detail detail(@PathVariable @Min(1) long personId,
                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return queries.detail(personId, principal.userId());
    }

    @GetMapping("/persons/{personId}/photo")
    public ResponseEntity<ByteArrayResource> photo(@PathVariable @Min(1) long personId,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        PersonQueryService.PhotoContent photo = queries.photo(personId, principal.userId());
        return ResponseEntity.ok().contentType(photoMediaType(photo.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(photo.filename(), StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .contentLength(photo.bytes().length).body(new ByteArrayResource(photo.bytes()));
    }

    @GetMapping("/persons/{personId}/archive")
    public ResponseEntity<ByteArrayResource> export(@PathVariable @Min(1) long personId,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        ArchiveExportService.GeneratedArchive archive = exports.generate(personId, principal.userId());
        return ResponseEntity.ok().contentType(XLSX)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(archive.filename(), StandardCharsets.UTF_8).build().toString())
            .contentLength(archive.bytes().length).body(new ByteArrayResource(archive.bytes()));
    }

    @PostMapping("/persons/exports")
    public ResponseEntity<ByteArrayResource> exportBatch(
        @Valid @RequestBody ArchiveExportDtos.BatchRequest request,
        @AuthenticationPrincipal AuthPrincipal principal) {
        BatchArchiveExportService.GeneratedBundle bundle = batchExports.generate(
            request.personIds(), principal.userId());
        return ResponseEntity.ok().contentType(ZIP)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(bundle.filename(), StandardCharsets.UTF_8).build().toString())
            .contentLength(bundle.bytes().length).body(new ByteArrayResource(bundle.bytes()));
    }

    @GetMapping("/family-members/search")
    public PersonDtos.FamilySearchPage familySearch(
        @RequestParam @NotBlank @Size(max = 128) String name,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.searchFamily(name, page, size);
    }

    private static MediaType photoMediaType(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> MediaType.IMAGE_PNG;
            case "image/jpeg" -> MediaType.IMAGE_JPEG;
            default -> throw new BusinessException(ErrorCode.INTERNAL_ERROR, "档案照片类型无效");
        };
    }
}
