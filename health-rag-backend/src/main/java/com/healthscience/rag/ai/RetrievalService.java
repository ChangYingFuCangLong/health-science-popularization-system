package com.healthscience.rag.ai;

import com.healthscience.rag.ai.model.RetrievalResult;

import java.util.List;

/**
 * 检索能力接口（框架无关，见架构约束 C1/C2）。
 *
 * <p><b>链路</b>：向量召回 + 关键词召回（MySQL ngram 全文索引）→ RRF 融合 →（可选）rerank。
 * 融合算法采用 RRF（Reciprocal Rank Fusion），兼顾两路召回且无需分数归一化。
 */
public interface RetrievalService {

    /**
     * 按指定策略召回候选片段。
     *
     * @param query        用户提问（多为改写后的检索 query）
     * @param retrieveMode 检索策略，取值见 {@link com.healthscience.rag.ai.model.RetrieveMode}
     * @param topK         返回条数
     * @param scopeFilter  检索范围（07 §3.3）：all = 全局库+个人库 / global = 仅全局库 /
     *                     personal = 仅个人库；传 null 视为 global
     * @param ownerUserId  当前用户（personal/all 语义需要；无登录语境传 null → 仅全局库）
     * @return 候选片段（按得分降序）+ 向量路 top1 余弦分数（置信度拒答用）+ 检索过程 trace
     */
    RetrievalResult retrieve(String query, String retrieveMode, int topK,
                             String scopeFilter, Long ownerUserId);

    /**
     * 文档解惑检索（B-31）：硬限定在会话文件集内（两路召回 + RRF，与合并检索同构）。
     * 就绪语义独立：文件集内只要有已向量化块即可（不依赖全局库 isReady）。
     */
    RetrievalResult retrieveInSession(String query, Long sessionId, int topK);

    /**
     * 索引是否就绪。
     *
     * <p>未就绪时上层应返回错误码 {@code 2003}，而不是给出错误答案
     * （向量与索引不匹配属静默失败，必须显式拦截）。
     */
    boolean isReady();
}
