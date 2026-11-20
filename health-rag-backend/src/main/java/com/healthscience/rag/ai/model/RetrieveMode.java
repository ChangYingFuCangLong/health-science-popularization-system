package com.healthscience.rag.ai.model;

/**
 * 检索策略常量。
 *
 * <p>取值同时写入 {@code chat_message.retrieve_mode} 与 {@code eval_result.strategy}，
 * 是论文消融实验的数据来源 —— 因此使用字符串常量而非枚举，便于直接落库与聚合查询。
 *
 * <p>{@code HYBRID_RERANK} 是 rerank 的对照实验组：rerank 已退出主链路（ADR-018），
 * 仅在此策略下启用。
 */
public final class RetrieveMode {

    /** 纯对话，不检索 */
    public static final String NONE = "none";

    /** 仅向量检索 */
    public static final String NAIVE = "naive";

    /** 向量 + 关键词（MySQL ngram 全文），RRF 融合 —— 主链路默认 */
    public static final String HYBRID = "hybrid";

    /** 混合检索 + rerank —— 仅实验组（ADR-018） */
    public static final String HYBRID_RERANK = "hybrid_rerank";

    private RetrieveMode() {
    }
}
