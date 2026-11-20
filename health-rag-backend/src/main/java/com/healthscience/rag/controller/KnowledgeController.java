package com.healthscience.rag.controller;

import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.model.dto.KnowledgeDTO;
import com.healthscience.rag.service.KnowledgeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端知识浏览，对应接口文档 §5.1–§5.3。
 *
 * <p>只读接口，任何登录用户均可访问（不做管理端权限区分）。
 */
@Tag(name = "知识浏览 /api/knowledge")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /** §5.1 分类列表 */
    @GetMapping("/categories")
    public R<List<KnowledgeDTO.CategoryVO>> categories() {
        return R.ok(knowledgeService.categories());
    }

    /** §5.2 文章列表 */
    @GetMapping("/articles")
    public R<PageResult<KnowledgeDTO.ArticleVO>> articles(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        UserContext.requireId();
        return R.ok(knowledgeService.articles(category, keyword, pageNum, pageSize));
    }

    /** §5.3 文章详情（正文由分块拼接还原） */
    @GetMapping("/articles/{id}")
    public R<KnowledgeDTO.ArticleDetailVO> articleDetail(@PathVariable Long id) {
        UserContext.requireId();
        return R.ok(knowledgeService.articleDetail(id));
    }

    /** §5.4 相关文章推荐（pgvector 质心近邻，limit ≤10；无向量返回空数组） */
    @GetMapping("/articles/{id}/related")
    public R<KnowledgeDTO.RelatedArticlesVO> related(
            @PathVariable Long id,
            @RequestParam(defaultValue = "5") int limit) {
        UserContext.requireId();
        return R.ok(new KnowledgeDTO.RelatedArticlesVO(knowledgeService.related(id, limit)));
    }
}
