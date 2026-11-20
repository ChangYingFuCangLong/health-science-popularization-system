package com.healthscience.rag.common.result;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页响应体单测（M9/B-7）：字段名与契约 02 §1.2 一致。
 */
class PageResultTest {

    @Test
    @DisplayName("of(IPage)：total/pages/current/size/records 逐项一致")
    void fromMybatisPlusPage() {
        Page<String> page = new Page<>(2, 10, 25);
        page.setRecords(List.of("a", "b"));

        PageResult<String> result = PageResult.of(page);

        assertThat(result.total()).isEqualTo(25);
        assertThat(result.pages()).isEqualTo(3);
        assertThat(result.current()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.records()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("内存分页：最后一页只含剩余条数")
    void inMemoryPagingSlicesTail() {
        List<Integer> all = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        PageResult<Integer> result = PageResult.of(all, 2, 10);

        assertThat(result.total()).isEqualTo(12);
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.records()).containsExactly(11, 12);
    }

    @Test
    @DisplayName("内存分页：页码越界返回空 records，不抛异常")
    void inMemoryPagingBeyondRange() {
        PageResult<Integer> result = PageResult.of(List.of(1, 2, 3), 99, 10);

        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isEqualTo(3);
    }
}
