/* 导入 HTTP 边界：接收工作簿和逐项决定，身份与权限由服务端上下文解析。 */
package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.auth.AuthPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/imports")
public class ImportController {
    private final ImportBatchService service;
    private final ImportHistoryService history;

    public ImportController(ImportBatchService service, ImportHistoryService history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping("/history")
    public ImportDtos.HistoryPage history(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @AuthenticationPrincipal AuthPrincipal principal) {
        return history.history(page, size, principal.userId());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportDtos.BatchResponse precheck(@RequestPart("files") List<MultipartFile> files,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        return service.precheck(files, principal.userId());
    }

    @PostMapping(value = "/{itemId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ImportDtos.ItemResult decide(@PathVariable @Min(1) long itemId,
                                        @RequestBody DecisionRequest request,
                                        @AuthenticationPrincipal AuthPrincipal principal) {
        return service.decide(itemId, request.decision(), principal.userId());
    }

    public record DecisionRequest(String decision) {}
}
