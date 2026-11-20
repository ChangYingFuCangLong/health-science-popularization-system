package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.entity.KbChunk;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.mapper.KbChunkMapper;
import com.healthscience.rag.mapper.KbDocumentMapper;
import com.healthscience.rag.service.KbImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 语料导入实现（M5 引入，M9.5/B-10 改造，ADR-023）。
 *
 * <p><b>为什么不做解析与分块</b>：上游 Python 流水线（{@code collect → clean → chunk}）
 * 已产出清洗并分块好的 JSONL，重复实现既浪费又容易引入不一致，这里只负责
 * 落库 + 向量化。
 *
 * <p><b>向量内嵌的写入顺序（P1 根治）</b>：先向量化、后落库 ——
 * 分块行与它的向量在同一条 INSERT 里出现，结构上不可能再出现
 * "分块入库成功而向量静默丢失"。批量 embedding 最终失败的分块以
 * embedding = NULL 落库（pending 计数 +1），由管理端"重建索引"回补，
 * 索引状态随之显式变为"未就绪"而不是带缺口继续谎报就绪。
 *
 * <p><b>手工分批 + 节流</b>（V1 实测结论沿用）：10 条/批 + 每批 300ms 间隔，
 * 连续调用数百次不触发百炼限流；批量失败退避重试 3 次后降级逐条。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbImportServiceImpl implements KbImportService {

    /** 每批向量化条数（10 条 ≈ 0.65s，全量 2391 条约 2.6 分钟，实测可控区间） */
    private static final int EMBED_BATCH = 10;

    /** 单批最大尝试次数，超过后降级逐条 */
    private static final int MAX_ATTEMPTS = 3;

    /** 每批之后的节流间隔（毫秒）：无间隔连续调用会在数百次后触发云端限流 */
    @Value("${health.rag.embed-delay-ms:300}")
    private long embedDelayMs;

    /** 导入人：seed 数据里的 admin。M12 上传功能上线后应改为当前登录用户 */
    private static final Long DEFAULT_UPLOAD_BY = 1L;

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final EmbeddingBatchIngestor ingestor;
    private final ObjectMapper objectMapper;

    /** 上游分块文件的一行。字段名与 JSONL 严格对应（驼峰） */
    public record SourceChunk(
            String docId,
            String title,
            String category,
            String source,
            Integer sourceLevel,
            String publishDate,
            String collectDate,
            String url,
            String summary,
            List<String> keywords,
            String status,
            String chunkId,
            Integer chunkIndex,
            String chunkText,
            Integer chunkCharLen
    ) {
    }

    @Override
    public ImportResult importFromDir(Path dir, boolean rebuild) {
        long startAt = System.currentTimeMillis();
        File directory = dir.toFile();
        if (!directory.isDirectory()) {
            throw BizException.badRequest("目录不存在或不是目录: " + dir);
        }
        File[] files = directory.listFiles((d, name) -> name.endsWith(".jsonl"));
        if (files == null || files.length == 0) {
            throw BizException.badRequest("目录下没有 .jsonl 文件: " + dir);
        }
        // 排序保证多次导入顺序一致，便于对照
        Arrays.sort(files, Comparator.comparing(File::getName));

        if (rebuild) {
            clearAll();
        }
        // 非重建模式下跳过已导入过的源文件：长任务中断后重跑不会重复入库
        Set<String> alreadyImported = rebuild ? Set.of() : existingFileNames();

        int docCount = 0;
        int chunkCount = 0;
        int embeddedCount = 0;
        int pendingCount = 0;
        List<KbChunk> batch = new ArrayList<>(EMBED_BATCH);

        for (File file : files) {
            if (alreadyImported.contains(file.getName())) {
                log.info("[KbImport] 跳过已导入的 {}", file.getName());
                continue;
            }
            Map<String, List<SourceChunk>> grouped = readGrouped(file);
            if (grouped.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, List<SourceChunk>> entry : grouped.entrySet()) {
                List<SourceChunk> chunks = entry.getValue();
                SourceChunk first = chunks.get(0);

                KbDocument doc = new KbDocument();
                doc.setTitle(first.title());
                doc.setSource(first.source());
                doc.setCategory(first.category());
                doc.setFileType("jsonl");
                doc.setFilePath(file.getName());
                doc.setFileSize(file.length());
                doc.setStatus(KbDocument.STATUS_DONE);
                doc.setChunkCount(chunks.size());
                doc.setUploadBy(DEFAULT_UPLOAD_BY);
                documentMapper.insert(doc);
                docCount++;

                for (SourceChunk c : chunks) {
                    batch.add(toChunk(doc, first, c));
                    chunkCount++;
                    if (batch.size() >= EMBED_BATCH) {
                        int[] r = embedAndPersist(batch);
                        embeddedCount += r[0];
                        pendingCount += r[1];
                        batch.clear();
                    }
                }
            }
            log.info("[KbImport] {} 处理完毕，累计 {} 篇 / {} 块", file.getName(), docCount, chunkCount);
        }

        if (!batch.isEmpty()) {
            int[] r = embedAndPersist(batch);
            embeddedCount += r[0];
            pendingCount += r[1];
        }
        if (pendingCount > 0) {
            log.warn("[KbImport] 有 {} 条分块向量化失败，已以 NULL 向量入库（管理端重建索引可回填）", pendingCount);
        }

        int dimension = ingestor.dimension();
        return new ImportResult(docCount, chunkCount, embeddedCount, pendingCount,
                System.currentTimeMillis() - startAt, dimension);
    }

    /** SourceChunk → 实体。向量在 embedAndPersist 中附着（失败路径不携带，原样入库为 NULL） */
    private KbChunk toChunk(KbDocument doc, SourceChunk docLevel, SourceChunk c) {
        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(doc.getId());
        chunk.setChunkIndex(c.chunkIndex());
        chunk.setContent(c.chunkText());
        chunk.setCharCount(c.chunkCharLen());
        chunk.setMetaJson(metaJson(docLevel, c));
        LocalDateTime now = LocalDateTime.now();
        chunk.setCreateTime(now);
        chunk.setUpdateTime(now);
        return chunk;
    }

    private int[] embedAndPersist(List<KbChunk> batch) {
        return ingestor.embedAndInsert(batch);
    }

    private Set<String> existingFileNames() {
        List<Object> paths = documentMapper.selectObjs(
                new LambdaQueryWrapper<KbDocument>().select(KbDocument::getFilePath));
        return paths.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.toSet());
    }

    /** 按 docId 归组（LinkedHashMap 保持文件内顺序，chunkIndex 也因此稳定） */
    private Map<String, List<SourceChunk>> readGrouped(File file) {
        Map<String, List<SourceChunk>> grouped = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                SourceChunk c = objectMapper.readValue(trimmed, SourceChunk.class);
                if (c.docId() == null || c.chunkText() == null) {
                    continue;
                }
                grouped.computeIfAbsent(c.docId(), k -> new ArrayList<>()).add(c);
            }
        } catch (IOException e) {
            log.error("[KbImport] 读取失败，跳过文件: {}", file.getName(), e);
        }
        return grouped;
    }

    private void clearAll() {
        // F2（测试报告 §27.4）：重建是"清空重来"，改用物理删 —— 逻辑删会让全局库累积永不使用的
        // 僵尸记录（实测 1312 条），只增加备份与 VACUUM 成本。顺序不能反：分块按 document_id
        // 子查询定位，文档先删就删不到块，会留下孤儿分块（两表间无外键）。
        int c = chunkMapper.purgeGlobalChunks();
        int d = documentMapper.purgeGlobalDocs();
        log.info("[KbImport] 重建：物理删除全局库分块 {} 条、文档 {} 条（个人库不受影响）", c, d);
    }

    /** 供引用溯源展示的元数据（存 kb_chunk.meta_json，JSONB 列经 stringtype=unspecified 直写） */
    private String metaJson(SourceChunk doc, SourceChunk chunk) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "url", doc.url() == null ? "" : doc.url(),
                    "publishDate", doc.publishDate() == null ? "" : doc.publishDate(),
                    "sourceLevel", doc.sourceLevel() == null ? 0 : doc.sourceLevel(),
                    "keywords", doc.keywords() == null ? List.of() : doc.keywords(),
                    "chunkId", chunk.chunkId() == null ? "" : chunk.chunkId()));
        } catch (Exception e) {
            log.warn("[KbImport] meta_json 序列化失败", e);
            return "{}";
        }
    }
}
