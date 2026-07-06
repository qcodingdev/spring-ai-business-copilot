package dev.qcoding.businesscopilot.commonweb.api;

import java.util.List;

/**
 * Generic paginated response wrapper.
 *
 * <p>统一分页响应。第一版只承载分页元信息和数据列表，不耦合具体查询方言。</p>
 *
 * @param content      page content
 * @param page         zero-based page index
 * @param size         page size requested
 * @param totalElements total number of elements across all pages
 * @param totalPages   total number of pages
 * @param <T>          element type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /** Build a {@link PageResponse} from a page slice and the full total. */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / (double) size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    /** Build an empty page response for the given page/size. */
    public static <T> PageResponse<T> empty(int page, int size) {
        return of(List.of(), page, size, 0L);
    }
}
