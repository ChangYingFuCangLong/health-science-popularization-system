package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.entity.KbChunk;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.mapper.KbChunkMapper;
import com.healthscience.rag.mapper.KbDocumentMapper;
import com.healthscience.rag.model.dto.KnowledgeDTO;
import com.healthscience.rag.service.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户端知识浏览实现（§5.1–§5.3）。
 *
 * <p><b>详情为什么由分块拼接</b>：入库时只保留了分块文本（上游流水线已切分），
 * 没有单独存整篇原文，因此按 {@code chunk_index} 顺序拼接即为完整正文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<KnowledgeDTO.CategoryVO> categories() {
        List<Map<String, Object>> rows = documentMapper.selectCategoryCounts();
        List<KnowledgeDTO.CategoryVO> vos = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object count = row.get("count");
            vos.add(new KnowledgeDTO.CategoryVO(
                    String.valueOf(row.get("name")),
                    count == null ? 0 : ((Number) count).intValue()));
        }
        return vos;
    }

    @Override
    public PageResult<KnowledgeDTO.ArticleVO> articles(String category, String keyword,
                                                       int pageNum, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), ARTICLE_PAGE_MAX);
        Page<KbDocument> page = new Page<>(Math.max(pageNum, 1), size);
        page.addOrder(OrderItem.desc("id"));

        LambdaQueryWrapper<KbDocument> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            q.eq(KbDocument::getCategory, category.trim());
        }
        if (StringUtils.hasText(keyword)) {
            q.like(KbDocument::getTitle, keyword.trim());
        }
        Page<KbDocument> result = documentMapper.selectPage(page, q);
        return PageResult.of(result, d -> new KnowledgeDTO.ArticleVO(
                d.getId(), d.getTitle(), d.getSource(), d.getCategory(),
                d.getChunkCount(), d.getCreateTime()));
    }

    @Override
    public KnowledgeDTO.ArticleDetailVO articleDetail(Long id) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw BizException.notFound("文章不存在");
        }
        List<KbChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getDocumentId, id)
                        .orderByAsc(KbChunk::getChunkIndex));

        StringBuilder content = new StringBuilder();
        // §5.3 [V2 变更]：按章节分组（保持文档顺序）；section 为空的历史块归入「正文」组
        java.util.LinkedHashMap<String, StringBuilder> groups = new java.util.LinkedHashMap<>();
        for (KbChunk c : chunks) {
            if (c.getContent() == null) {
                continue;
            }
            content.append(c.getContent()).append('\n');
            String key = c.getSection() == null || c.getSection().isBlank() ? "正文" : c.getSection();
            groups.computeIfAbsent(key, k -> new StringBuilder())
                    .append(c.getContent()).append('\n');
        }
        List<KnowledgeDTO.SectionVO> sections = groups.entrySet().stream()
                .map(e -> new KnowledgeDTO.SectionVO(e.getKey(), e.getValue().toString().strip()))
                .toList();
        return new KnowledgeDTO.ArticleDetailVO(
                doc.getId(),
                doc.getTitle(),
                doc.getSource(),
                doc.getCategory(),
                extractUrl(chunks),
                chunks.size(),
                content.toString().trim(),
                sections);
    }

    @Override
    public List<KnowledgeDTO.RelatedArticleVO> related(Long documentId, int limit) {
        if (documentMapper.selectById(documentId) == null) {
            throw BizException.notFound("文章不存在");
        }
        int capped = Math.min(Math.max(limit, 1), 10);
        return chunkMapper.selectRelatedDocuments(documentId, capped).stream()
                .map(r -> new KnowledgeDTO.RelatedArticleVO(
                        ((Number) r.get("id")).longValue(),
                        String.valueOf(r.get("title")),
                        r.get("category") == null ? "" : String.valueOf(r.get("category")),
                        r.get("similarity") == null ? 0.0 : ((Number) r.get("similarity")).doubleValue()))
                .toList();
    }

    /** 原文链接存在分块的 meta_json 里（导入时写入），取第一块即可 */
    private String extractUrl(List<KbChunk> chunks) {
        if (chunks.isEmpty() || chunks.get(0).getMetaJson() == null) {
            return null;
        }
        try {
            Map<?, ?> meta = objectMapper.readValue(chunks.get(0).getMetaJson(), Map.class);
            Object url = meta.get("url");
            return url == null ? null : String.valueOf(url);
        } catch (Exception e) {
            log.warn("解析 meta_json 失败，略过原文链接", e);
            return null;
        }
    }
}
