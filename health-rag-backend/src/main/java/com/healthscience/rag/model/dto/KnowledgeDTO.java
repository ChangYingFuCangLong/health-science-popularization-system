package com.healthscience.rag.model.dto;

import java.time.LocalDateTime;

import java.util.List;

/**
 * 知识浏览的传输对象，对应接口文档 §5.1–§5.3。
 */
public final class KnowledgeDTO {

    private KnowledgeDTO() {
    }

    /** §5.1 分类列表项 */
    public record CategoryVO(String name, Integer count) {
    }

    /** §5.2 文章列表项 */
    public record ArticleVO(
            Long id,
            String title,
            String source,
            String category,
            Integer chunkCount,
            LocalDateTime createTime
    ) {
    }

    /** §5.4 相关文章项（pgvector 质心近邻，similarity ∈ [0,1]） */
    public record RelatedArticleVO(
            Long id,
            String title,
            String category,
            Double similarity
    ) {
    }

    /** §5.4 相关文章响应 */
    public record RelatedArticlesVO(java.util.List<RelatedArticleVO> articles) {
    }

    /** §5.3 章节组（V2 变更）：结构化分块按章节分组；V1 历史块归入「正文」组 */
    public record SectionVO(String title, String content) {
    }

    /** §5.3 文章详情：{@code content} 由该文档的全部分块按序拼接还原 */
    public record ArticleDetailVO(
            Long id,
            String title,
            String source,
            String category,
            String url,
            Integer chunkCount,
            String content,
            List<SectionVO> sections
    ) {
    }
}
