/* 导入 API DTO：定义批次结果、逐项状态、重复摘要、永久记录和允许的用户决定。 */
package com.company.personnelarchive.archiveimport;

import java.util.List;

public final class ImportDtos {
    private ImportDtos() {}
    public record DuplicateSummary(String personName, String identityCardLastFour, String existingEmployeeNo,
                                   String incomingEmployeeNo, String existingUpdatedAt) {}
    public record ItemResult(@tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class) long itemId,
                             String fileName, String status, String code, String message,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             DuplicateSummary duplicate) {}
    public record BatchResponse(@tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class) long batchId,
                                List<ItemResult> items) {}
    public record HistoryItem(
        @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
        long itemId,
        @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
        long batchId,
        String operatorName, String fileName, String status, String code, String message, String processedAt) {}
    public record HistoryPage(List<HistoryItem> content, int page, int size,
                              long totalElements, int totalPages) {}
}
