package com.healthscience.rag.common.exception;

import com.healthscience.rag.common.result.ErrorCode;

/**
 * 业务异常。
 *
 * <p>由 {@link GlobalExceptionHandler} 统一转换为 {@code R} 响应，只记录 warn 级别日志，
 * 不打印完整堆栈 —— 业务异常是预期内的分支，不是故障。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(ErrorCode.BAD_REQUEST, message);
    }

    public int getCode() {
        return code;
    }

    /* ---------- 常用工厂：把错误码与文案收敛在一处，避免各处硬编码 ---------- */

    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }

    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    /** 拒答：知识库中无相关内容（前端需展示统一文案并引导就医） */
    public static BizException noRelevantKnowledge() {
        return new BizException(ErrorCode.NO_RELEVANT_KNOWLEDGE, "知识库中暂无相关内容");
    }

    /** 向量索引未就绪。宁可显式失败，也不给出错误答案 */
    public static BizException vectorIndexNotReady() {
        return new BizException(ErrorCode.VECTOR_INDEX_NOT_READY, "向量索引未就绪，请联系管理员");
    }
}
