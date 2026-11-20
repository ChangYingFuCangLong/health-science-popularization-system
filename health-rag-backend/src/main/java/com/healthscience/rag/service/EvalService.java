package com.healthscience.rag.service;

import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.model.dto.AdminDTO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 评测与实验，对应接口文档 §八（B-22 闭环已齐备）。
 *
 * <p><b>论文数据的产出链路</b>：录入评测题 → 执行实验（多策略检索 + 可选生成 + LLM 自动判分）
 * → 人工复核 → 查询 / 汇总。§7.5 的汇总接口直接产出论文「实验与评估」章节的对比表。
 */
public interface EvalService {

    /** 检索 topK：命中率指标为 hitRate5，因此固定取 5 */
    int EVAL_TOP_K = 5;

    /** §7.2 评测集列表 */
    PageResult<AdminDTO.EvalItemVO> dataset(String category, int pageNum, int pageSize);

    /** §7.1 新增评测问题，返回新记录 ID */
    Long addDataset(String question, String groundTruth, List<Long> refChunkIds,
                    String category, Integer difficulty);

    /**
     * §7.3 执行实验。
     *
     * @param strategies 检索策略（none/naive/hybrid/hybrid_rerank）
     * @param datasetIds 指定题目；为空表示全部
     * @param generate   true 时逐题生成答案，并在批次结束后跑 LLM-as-judge 自动判分
     *                   （{@code score_source='AUTO'}）；false 时只做检索评测，
     *                   HitRate/MRR 照常产出，但 faithfulness 留空
     * @return 批次号
     */
    String runExperiment(List<String> strategies, List<Long> datasetIds, boolean generate);

    /** §7.4 实验结果查询 */
    PageResult<AdminDTO.EvalResultVO> results(String batchNo, String strategy,
                                              int pageNum, int pageSize);

    /**
     * 🟡 B-22 人工复核：覆盖指定结果的分数并置 {@code score_source='MANUAL'}。
     *
     * <p>这是双轨制的第二轨：自动分用于跑批效率，人工分用于论文抽检一致率的核对。
     *
     * @param faithfulness    忠实度 1~5（保留两位小数），null 表示不改该项
     * @param citationCorrect 引用正确 0/1，null 表示不改该项
     */
    void review(Long resultId, BigDecimal faithfulness, Integer citationCorrect);

    /** §7.5 实验指标汇总；batchNo 为空时取最近一次批次 */
    AdminDTO.EvalSummaryVO summary(String batchNo);
}
