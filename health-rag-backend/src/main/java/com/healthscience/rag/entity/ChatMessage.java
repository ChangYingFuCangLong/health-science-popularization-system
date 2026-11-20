package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答消息实体，对应 {@code chat_message} 表。
 *
 * <p>{@code referencesJson} 存引用片段的 JSON 文本（摘要快照，非全文），
 * 历史引用不随语料重新分块而失效（ADR-012）。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 冗余用户 ID，便于按用户统计 */
    private Long userId;

    /** user / assistant */
    private String role;

    private String content;

    /** 实际用于检索的改写后查询（多轮消解；不检索时为 NULL，02 §6.9） */
    private String queryText;

    /** 追问建议缓存 JSON：["问题1","问题2",...]（04-裁决 §4.3，首次生成后写入） */
    private String suggestionsJson;

    /** 检索过程回放 JSON（B-23，§6.10）：两路召回/RRF/rerank/置信度/decision */
    private String traceJson;

    /** 对照解答来源（M16 v4.0，B-27）：global 系统答案 / fused 融合答案；NULL = 普通单答案 */
    private String answerSource;

    /** 对照解答组 ID（M16 v4.0）：同一组双答案共享一个 UUID；NULL = 普通单答案 */
    private String conflictGroupId;

    /** 引用片段 JSON：[{chunkId,docId,doc,section,digest,score}] */
    private String referencesJson;

    /** none / naive / hybrid / hybrid_rerank */
    private String retrieveMode;

    private Integer tokenIn;

    private Integer tokenOut;

    /** 响应总耗时（毫秒） */
    private Integer latencyMs;

    /** 首字延迟（毫秒），推送首个 delta 时打点（ADR-017） */
    private Integer firstTokenMs;

    /** 完成状态：0 未完成（流中断，内容已保留），1 已完成 */
    private Integer finishStatus;

    /** 是否拒答：0 否，1 是 */
    private Boolean isRejected;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;

    public static final int FINISH_INCOMPLETE = 0;
    public static final int FINISH_COMPLETED = 1;

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
}
