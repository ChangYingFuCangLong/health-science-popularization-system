package com.healthscience.rag.common.result;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 统一响应体，对应接口文档 §1.1。
 *
 * <pre>
 * { "code": 200, "message": "success", "data": {} }
 * </pre>
 *
 * @param code    状态码，200 表示成功，其余见 {@link ErrorCode}
 * @param message 提示信息
 * @param data    业务数据
 */
public record R<T>(int code, String message, T data) {

    private static final String SUCCESS_MESSAGE = "success";

    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCode.SUCCESS, SUCCESS_MESSAGE, data);
    }

    public static R<Void> ok() {
        return new R<>(ErrorCode.SUCCESS, SUCCESS_MESSAGE, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    /**
     * 仅供服务端代码判断，不参与 JSON 序列化。
     *
     * <p>加 {@code @JsonIgnore} 的原因：Jackson 默认会把 {@code isSuccess()} 序列化成
     * {@code "success": true} 字段，而接口文档 §1.1 只定义了 code / message / data 三个字段，
     * 多出的字段属于契约偏离。
     */
    @JsonIgnore
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS;
    }
}

