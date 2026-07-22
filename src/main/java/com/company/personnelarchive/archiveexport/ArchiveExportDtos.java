/* 批量导出 HTTP DTO：人员标识符使用字符串，避免桌面端 JavaScript 大整数精度损失。 */
package com.company.personnelarchive.archiveexport;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ArchiveExportDtos {
    private ArchiveExportDtos() {}

    public record BatchRequest(
        @NotEmpty @Size(max = 100)
        List<@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String> personIds) {}
}
