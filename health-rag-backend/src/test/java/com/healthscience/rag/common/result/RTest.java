package com.healthscience.rag.common.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应体单测（M9/B-7）：契约 02 §1.1 只有 code/message/data 三个字段。
 */
class RTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("ok 携带 data，code=200")
    void okCarriesData() {
        R<String> r = R.ok("payload");

        assertThat(r.code()).isEqualTo(ErrorCode.SUCCESS);
        assertThat(r.data()).isEqualTo("payload");
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("fail 携带 code 与 message，data 为 null")
    void failCarriesCodeAndMessage() {
        R<Void> r = R.fail(ErrorCode.BAD_CREDENTIALS, "用户名或密码错误");

        assertThat(r.code()).isEqualTo(1002);
        assertThat(r.message()).isEqualTo("用户名或密码错误");
        assertThat(r.data()).isNull();
        assertThat(r.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("isSuccess 不参与序列化：响应体不出现 success 字段（契约防漂移）")
    void isSuccessNotSerialized() throws Exception {
        String json = objectMapper.writeValueAsString(R.fail(400, "参数错误"));

        assertThat(json).contains("\"code\":400").contains("\"message\"").contains("\"data\":null");
        assertThat(json).doesNotContain("success");
    }
}
