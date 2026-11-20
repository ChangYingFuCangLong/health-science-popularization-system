package com.healthscience.rag.ai.model;

/**
 * 向量召回的一条命中（pgvector，ADR-023）。
 *
 * <p>与 V1 SimpleVectorStore 命中的区别：向量内嵌在 {@code kb_chunk.embedding}，
 * SQL JOIN 文档表一次带回溯源元数据，无需再按 embeddingId 反查映射。
 *
 * @param id         分块 ID（→ {@code kb_chunk.id}）
 * @param documentId 所属文档 ID
 * @param content    分块原文
 * @param score      余弦相似度（1 - 距离），∈ [-1,1] —— B-16 置信度拒答直接用它
 * @param title      来源文档标题
 * @param source     来源机构
 * @param category   分类（SQL 侧已支持按此过滤，为按科问答预留）
 * @param section    章节路径（历史语料为 NULL）
 * @param scope      来源归属：global（权威知识库）/ personal（我的资料，M15）
 */
public record VectorHit(
        Long id,
        Long documentId,
        String content,
        double score,
        String title,
        String source,
        String category,
        String section,
        String scope
) {
}
