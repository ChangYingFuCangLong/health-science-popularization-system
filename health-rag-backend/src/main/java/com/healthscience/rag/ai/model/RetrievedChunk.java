package com.healthscience.rag.ai.model;

import java.util.Map;

/**
 * 检索召回的分块（内部使用）。
 *
 * <p>与 {@link ChunkRef} 的区别：本类含<b>原文</b>，用于拼装 Prompt；
 * {@code ChunkRef} 只含摘要，用于对外展示与溯源。
 *
 * @param chunkId  分块 ID（→ {@code kb_chunk.id}）
 * @param content  分块原文
 * @param score    相关性得分（融合后的归一化得分）
 * @param metadata 附加元数据：来源文档、章节路径、分类等
 */
public record RetrievedChunk(
        Long chunkId,
        String content,
        double score,
        Map<String, Object> metadata
) {

    /** 取元数据中的字符串字段，缺失时返回空串（避免拼装 Prompt 时出现 null） */
    public String meta(String key) {
        if (metadata == null) {
            return "";
        }
        Object v = metadata.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
