package com.healthscience.rag.ai.model;

/**
 * 关键词召回的一条命中（MySQL ngram 全文索引）。
 *
 * <p>与向量召回的区别：向量按语义相似度排序，本类按 {@code MATCH...AGAINST} 的
 * BM25 类得分排序 —— 两路互补，由 RRF 融合。
 *
 * @param id         分块 ID（→ {@code kb_chunk.id}）
 * @param documentId 所属文档 ID
 * @param content    分块原文
 * @param section    章节路径（V1 JSONL 历史语料为 NULL）
 * @param scope      来源归属：global / personal（M15）
 * @param score      全文检索得分
 */
public record KeywordHit(
        Long id,
        Long documentId,
        String content,
        String section,
        String scope,
        double score
) {
}
