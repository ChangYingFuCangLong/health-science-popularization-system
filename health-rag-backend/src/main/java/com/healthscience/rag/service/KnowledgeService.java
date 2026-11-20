package com.healthscience.rag.service;

import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.model.dto.KnowledgeDTO;

import java.util.List;

/**
 * 用户端知识浏览，对应接口文档 §5.1–§5.3。
 */
public interface KnowledgeService {

    /** 单页文章条数上限 */
    int ARTICLE_PAGE_MAX = 100;

    /** §5.1 分类列表（按文档数倒序） */
    List<KnowledgeDTO.CategoryVO> categories();

    /** §5.2 文章列表：可按分类与标题关键词筛选 */
    PageResult<KnowledgeDTO.ArticleVO> articles(String category, String keyword, int pageNum, int pageSize);

    /** §5.3 文章详情：正文由分块按序拼接还原 */
    KnowledgeDTO.ArticleDetailVO articleDetail(Long id);

    /**
     * §5.4 相关文章推荐（M12/B-21，pgvector 质心近邻，排除本文档）。
     * 文档无已向量化分块时返回空列表（契约：非 404）。
     */
    List<KnowledgeDTO.RelatedArticleVO> related(Long documentId, int limit);
}
