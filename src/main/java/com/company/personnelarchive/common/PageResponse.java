/* 通用分页 DTO：明确返回内容、页码、页大小、总数和总页数。 */
package com.company.personnelarchive.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        int pages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(items, page, size, total, pages);
    }
}
