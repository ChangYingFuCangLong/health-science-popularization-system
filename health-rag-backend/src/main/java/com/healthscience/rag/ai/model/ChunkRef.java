package com.healthscience.rag.ai.model;

/**
 * 引用片段（对外展示用），对应接口文档 4.6 的 {@code reference.chunks[]}。
 *
 * @param chunkId 分块 ID
 * @param docId   来源文档 ID（→ {@code kb_document.id}）。前端据此跳转原文页，
 *                是「可溯源」从口号变成闭环的关键字段（ADR-019）
 * @param doc     来源文档标题
 * @param section 章节路径
 * @param digest  原文摘要（前 100 字）
 * @param score   相关性得分
 * @param scope   来源归属：global（权威知识库）/ personal（我的资料，M15）；
 *                历史消息无该字段 → 前端按 global 兜底渲染
 */
public record ChunkRef(
        Long chunkId,
        Long docId,
        String doc,
        String section,
        String digest,
        double score,
        String scope
) {
}
