package com.healthscience.rag.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应体，对应接口文档 §1.2。
 *
 * <pre>
 * { "total": 128, "pages": 13, "current": 1, "size": 10, "records": [] }
 * </pre>
 *
 * <p>字段名与文档严格一致，不要改成 records/pageNum 之类的惯用写法。
 */
public record PageResult<T>(
        long total,
        long pages,
        long current,
        long size,
        List<T> records
) {

    /** 由 MyBatis-Plus 分页对象直接构造 */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getPages(),
                page.getCurrent(), page.getSize(), page.getRecords());
    }

    /** 由实体分页转换为 VO 分页 */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(mapper).toList());
    }

    /**
     * 内存分页：已经拿到全部数据时按页切分。
     *
     * <p>用于自定义 SQL（跨表/含子查询）不便在 SQL 层分页的场景 ——
     * 数据量小时简单可靠；数据量大时应改回 SQL LIMIT + count。
     */
    public static <T> PageResult<T> of(List<T> all, long current, long size) {
        long total = all.size();
        long pages = size <= 0 ? 0 : (total + size - 1) / size;
        int from = (int) Math.min((current - 1) * size, total);
        int to = (int) Math.min(from + size, total);
        return new PageResult<>(total, pages, current, size, all.subList(from, to));
    }

    public static <T> PageResult<T> empty(long current, long size) {
        return new PageResult<>(0L, 0L, current, size, List.of());
    }
}
