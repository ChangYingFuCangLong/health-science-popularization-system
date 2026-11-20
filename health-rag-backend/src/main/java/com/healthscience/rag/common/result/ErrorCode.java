package com.healthscience.rag.common.result;

/**
 * 错误码常量，与接口文档 §1.3 严格一致。
 *
 * <p>改动本类前必须先改 {@code docs/接口文档.md} §1.3，避免前后端约定漂移。
 */
public final class ErrorCode {

    /* ---------- 框架级 ---------- */
    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int INTERNAL_ERROR = 500;

    /* ---------- 用户域 1xxx ---------- */
    /** 用户名已存在 */
    public static final int USERNAME_EXISTS = 1001;
    /** 用户名或密码错误 */
    public static final int BAD_CREDENTIALS = 1002;
    /** 账号已被禁用 */
    public static final int ACCOUNT_DISABLED = 1003;
    /** 原密码错误 */
    public static final int WRONG_OLD_PASSWORD = 1004;

    /* ---------- 知识库与 RAG 域 2xxx ---------- */
    /** 文档解析失败 */
    public static final int DOC_PARSE_FAILED = 2001;
    /** 知识库中无相关内容（拒答。前端需展示统一文案并引导就医） */
    public static final int NO_RELEVANT_KNOWLEDGE = 2002;
    /** 向量索引未就绪 */
    public static final int VECTOR_INDEX_NOT_READY = 2003;
    /** 语音识别失败（音频为空/格式不支持/引擎失败，M11） */
    public static final int ASR_FAILED = 2005;
    /** 个人库配额超限（M15：文档数/总大小/总块数任一超限，07 §5.1） */
    public static final int KB_QUOTA_EXCEEDED = 2006;
    /** 个人库文件不符合要求（M15：格式不支持/超大小上限/未提取到有效文本） */
    public static final int KB_FILE_INVALID = 2007;
    /** 画像未填写（M16：创建专属对话的前置校验） */
    public static final int PORTRAIT_NOT_FILLED = 2008;

    /* ---------- 健康档案域 3xxx ---------- */
    /** 健康指标取值越界（区间见接口文档 02 §10.2） */
    public static final int HEALTH_METRIC_OUT_OF_RANGE = 3001;

    private ErrorCode() {
    }
}
