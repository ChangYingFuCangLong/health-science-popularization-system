package com.healthscience.rag.ai.model;

import java.util.List;
import java.util.Map;

/**
 * 一次检索的结果（任务卡 B-16/B-23）。
 *
 * <p>除命中列表外，额外携带：
 * <ul>
 *   <li><b>向量路原始 top1 余弦相似度</b> —— 置信度拒答必须用余弦分数判定
 *       （RRF 分数域无语义，见 SafetyPolicy）；</li>
 *   <li><b>检索过程 trace</b>（任务卡 B-23，§6.10 检索回放的数据源）——
 *       两路召回明细 / 融合排序 / rerank 是否参与，由检索层组装、
 *       编排层补充问题与决策字段后随消息落库 {@code chat_message.trace_json}。</li>
 * </ul>
 *
 * @param chunks         融合后的候选片段（RRF 分数），已按分数降序
 * @param vectorTopScore 向量路最高余弦相似度；向量路零命中时为 0.0
 * @param trace          检索过程明细（元数据级，不含正文）
 */
public record RetrievalResult(
        List<RetrievedChunk> chunks,
        double vectorTopScore,
        Map<String, Object> trace,
        Map<String, Object> conflict
) {

    public static RetrievalResult empty() {
        return new RetrievalResult(List.of(), 0.0, Map.of(), null);
    }
}
