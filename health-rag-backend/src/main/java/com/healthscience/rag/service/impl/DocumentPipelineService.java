package com.healthscience.rag.service.impl;

import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.entity.KbChunk;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.mapper.KbChunkMapper;
import com.healthscience.rag.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 文档处理流水线（M15 自 AdminServiceImpl 提取，09 §八 决策 2）。
 *
 * <p><b>职责</b>：上传受理（校验→落盘→建档）→ 异步处理（Tika 抽取 → 结构感知分块 →
 * 批量向量化 → 状态机流转）→ 重建（作废→游标回填）。<b>scope/owner 参数化</b>：
 * 全局库与个人库（07）共用同一条管道，差异只在归属写入、落盘目录与配额挂钩。
 *
 * <p><b>并发模型</b>：单线程执行器串行处理——Ollama bge-m3 是单实例串行瓶颈，
 * 并发向量化只会互相拖慢（07 §八 算力争抢）；rebuilding 标志互斥全量重建。
 *
 * <p><b>scope 纪律（07 §2.3）</b>：全量重建（documentId=null）只作用于全局库，
 * clearEmbeddings / selectPendingEmbedding <b>成对同口径</b>——个人库向量既不被
 * 管理端清掉、也不被管理端回填，全站 isReady() 不受个人库 pending 影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentPipelineService {

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final EmbeddingBatchIngestor ingestor;
    private final DocumentChunker chunker;

    @Value("${health.rag.upload-dir:./data/uploads}")
    private String uploadDir;

    @Value("${health.rag.upload-max-size-mb:20}")
    private int uploadMaxSizeMb;

    @Value("${health.rag.upload-allowed-types:txt,md,html,htm,pdf,docx}")
    private String uploadAllowedTypes;

    @Value("${health.rag.embed-delay-ms:300}")
    private long embedDelayMs;

    /** 重建时每批向量化条数 */
    private static final int REBUILD_BATCH = 25;

    /** 单批最大尝试次数，超过后跳过该批（向量留 NULL，待下次重建补齐） */
    private static final int MAX_EMBED_ATTEMPTS = 3;

    /** 个人库单文档块数上限（07 §5.2，防超长文档拖垮异步任务） */
    public static final int PERSONAL_SINGLE_DOC_MAX_CHUNKS = 500;

    /** 上传流水线单线程执行器（守护线程不阻碍 JVM 退出） */
    private final ExecutorService uploadExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "kb-upload");
                t.setDaemon(true);
                return t;
            });

    /** 处理中的预估总块数（内存值，重启丢失——僵尸记录由 Runner 重置为失败态） */
    private final ConcurrentHashMap<Long, Integer> processingTotals =
            new ConcurrentHashMap<>();

    /** 重建并发保护：同时重建会造成批间游标错乱与重复计算 */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    // ==================== 上传受理 ====================

    /**
     * 全局库上传（管理端，02 §6.2）：落盘 {@code {upload-dir}/}。
     */
    public Long submitGlobal(String originalFilename, byte[] content, String title,
                             String category, String source, Long operatorId) {
        String ext = validate(originalFilename, content);
        Path path = save(originalFilename, ext, content, null);
        return createAndSchedule(KbDocument.SCOPE_GLOBAL, null, originalFilename, content.length,
                title, category, source, operatorId, ext, path.getFileName().toString(), path, null);
    }

    /**
     * 个人库上传（07 §5.3 #46）：落盘 {@code {upload-dir}/user-kb/{userId}/}（按用户隔离，
     * 文件名服务端生成杜绝路径穿越）；chunkQuota = 该用户剩余块数配额（超限处理中失败，
     * failReason 说明，状态 3）。
     */
    public Long submitPersonal(Long userId, String originalFilename, byte[] content,
                               String title, Integer chunkQuota) {
        String ext = validate(originalFilename, content);
        Path path = save(originalFilename, ext, content, userId);
        return createAndSchedule(KbDocument.SCOPE_PERSONAL, userId, originalFilename, content.length,
                title, null, null, userId, ext, path.getFileName().toString(), path, chunkQuota);
    }

    private String validate(String originalFilename, byte[] content) {
        if (originalFilename == null || originalFilename.isBlank() || content == null || content.length == 0) {
            throw BizException.badRequest("文件不能为空");
        }
        if (content.length > (long) uploadMaxSizeMb * 1024 * 1024) {
            throw BizException.badRequest("文件超过 " + uploadMaxSizeMb + "MB 上限");
        }
        String ext = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase() : "";
        Set<String> allowed = Arrays.stream(uploadAllowedTypes.split(","))
                .map(String::trim).map(String::toLowerCase).collect(Collectors.toSet());
        if (!allowed.contains(ext)) {
            throw BizException.badRequest("不支持的文件类型: " + ext + "（允许 " + uploadAllowedTypes + "）");
        }
        return ext;
    }

    /** 落盘：全局库在根目录；个人库 user-kb/{userId}/；文档解惑 kb-qa/{userId}/ */
    private Path save(String originalFilename, String ext, byte[] content, Long ownerUserId) {
        return save(originalFilename, ext, content, null,
                ownerUserId == null ? null : java.nio.file.Path.of("user-kb", String.valueOf(ownerUserId)));
    }

    private Path save(String originalFilename, String ext, byte[] content, Long ownerUserId, java.nio.file.Path subdir) {
        try {
            Path dir = subdir == null ? Path.of(uploadDir) : Path.of(uploadDir).resolve(subdir);
            Files.createDirectories(dir);
            String stored = System.currentTimeMillis() + "_" + UUID.randomUUID() + "." + ext;
            Path path = dir.resolve(stored);
            Files.write(path, content);
            return path;
        } catch (Exception e) {
            log.error("[Pipeline] 文件落盘失败", e);
            throw BizException.badRequest("文件保存失败，请重试");
        }
    }

    private Long createAndSchedule(String scope, Long ownerUserId, String originalFilename, long fileSize,
                                   String title, String category, String source, Long operatorId,
                                   String ext, String storedName, Path storedPath, Integer chunkQuota) {
        KbDocument doc = new KbDocument();
        doc.setTitle(title == null || title.isBlank()
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                : title.trim());
        doc.setSource(source);
        doc.setCategory(category);
        doc.setFileType(ext);
        doc.setFilePath(storedName);
        doc.setFileSize(fileSize);
        doc.setStatus(KbDocument.STATUS_PARSING);   // 上传即受理：0 待处理未用
        doc.setChunkCount(0);
        doc.setUploadBy(operatorId);
        doc.setScope(scope);
        doc.setOwnerUserId(ownerUserId);
        LocalDateTime now = LocalDateTime.now();
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        documentMapper.insert(doc);
        Long docId = doc.getId();

        uploadExecutor.submit(() -> processDocument(docId, storedPath, ext, chunkQuota));
        log.info("[Pipeline] 文档已受理 id={} scope={} owner={}", docId, scope, ownerUserId);
        return docId;
    }

    /**
     * 解析 → 结构感知分块 → 批量向量化落库 → 状态机流转。
     * chunkQuota 非空时（个人库）在写库前校验总块数配额，超限置 FAILED（failReason 说明）。
     */
    private void processDocument(Long documentId, Path storedPath, String ext, Integer chunkQuota) {
        try {
            String raw = extractText(storedPath, ext);
            String chunkerType = switch (ext) {
                case "md" -> "md";
                case "html", "htm", "docx", "pdf" -> "html";
                default -> "txt";
            };
            // D6：个人库的纯文本按中文小节切分（全局库保持原口径）
            String docScope = scopeOf(documentId);
            boolean personalScope = KbDocument.SCOPE_PERSONAL.equals(docScope);
            List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(raw, chunkerType, personalScope);
            if (drafts.isEmpty()) {
                throw new IllegalStateException("文档未提取到有效文本");
            }
            if (chunkQuota != null && drafts.size() > chunkQuota) {
                throw new IllegalStateException("超出个人库块数配额：本文档需 "
                        + drafts.size() + " 块，剩余配额 " + chunkQuota + " 块（错误码 2006）");
            }
            if (personalScope && drafts.size() > PERSONAL_SINGLE_DOC_MAX_CHUNKS) {
                throw new IllegalStateException("单文档块数超过上限 " + PERSONAL_SINGLE_DOC_MAX_CHUNKS);
            }
            processingTotals.put(documentId, drafts.size());

            KbDocument docRef = documentMapper.selectById(documentId);
            String safeTitle = docRef == null || docRef.getTitle() == null ? "" :
                    docRef.getTitle().replace("\\", "\\\\").replace("\"", "\\\"");
            List<KbChunk> batch = new ArrayList<>(EmbeddingBatchIngestor.EMBED_BATCH);
            for (DocumentChunker.ChunkDraft d : drafts) {
                KbChunk chunk = new KbChunk();
                chunk.setDocumentId(documentId);
                chunk.setChunkIndex(d.index());
                chunk.setSection(d.section());
                chunk.setContent(d.content());
                chunk.setCharCount(d.content().length());
                chunk.setMetaJson("{\"title\":\"" + safeTitle + "\"}");
                LocalDateTime now = LocalDateTime.now();
                chunk.setCreateTime(now);
                chunk.setUpdateTime(now);
                batch.add(chunk);
                if (batch.size() >= EmbeddingBatchIngestor.EMBED_BATCH) {
                    ingestor.embedAndInsert(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                ingestor.embedAndInsert(batch);
            }

            KbDocument done = new KbDocument();
            done.setId(documentId);
            done.setStatus(KbDocument.STATUS_DONE);
            done.setChunkCount(drafts.size());
            done.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(done);
            log.info("[Pipeline] 文档处理完成 id={}，{} 块", documentId, drafts.size());
        } catch (Exception e) {
            log.error("[Pipeline] 文档处理失败 id={}", documentId, e);
            try {
                KbDocument failed = new KbDocument();
                failed.setId(documentId);
                failed.setStatus(KbDocument.STATUS_FAILED);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                failed.setFailReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
                failed.setUpdateTime(LocalDateTime.now());
                documentMapper.updateById(failed);
            } catch (Exception ex) {
                log.error("[Pipeline] 失败态标记写库失败 id={}", documentId, ex);
            }
        } finally {
            processingTotals.remove(documentId);
        }
    }

    /** 文档归属（个人库单文档块数上限校验用）；查不到视为非个人库 */
    private String scopeOf(Long documentId) {
        KbDocument doc = documentMapper.selectById(documentId);
        return doc == null ? null : doc.getScope();
    }

    /** Tika 抽取：pdf/docx/html → XHTML（保留标题结构）；txt/md 直接读字符串 */
    private String extractText(Path storedPath, String ext) throws Exception {
        if ("txt".equals(ext) || "md".equals(ext)) {
            return Files.readString(storedPath);
        }
        var parser = new org.apache.tika.parser.AutoDetectParser();
        var out = new java.io.ByteArrayOutputStream();
        var handler = new org.apache.tika.sax.ToXMLContentHandler(out, "UTF-8");
        var metadata = new org.apache.tika.metadata.Metadata();
        try (var in = Files.newInputStream(storedPath)) {
            parser.parse(in, handler, metadata);
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 处理中的预估总块数（进度接口用）；未在处理中返回 null */
    public Integer processingTotal(Long documentId) {
        return processingTotals.get(documentId);
    }

    /**
     * 文档解惑文件上传（B-31）：落盘 {upload-dir}/kb-qa/{userId}/；scope='file'
     * （永不参与合并检索）；文件与会话的绑定由调用方写入 qa_session_document。
     */
    public Long submitFile(Long sessionId, Long userId, String originalFilename, byte[] content, String title) {
        String ext = validate(originalFilename, content);
        Path path = save(originalFilename, ext, content, null,
                java.nio.file.Path.of("kb-qa", String.valueOf(userId)));
        KbDocument doc = new KbDocument();
        doc.setTitle(title == null || title.isBlank()
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.')) : title.trim());
        doc.setFileType(ext);
        doc.setFilePath(path.getFileName().toString());
        doc.setFileSize((long) content.length);
        doc.setStatus(KbDocument.STATUS_PARSING);
        doc.setChunkCount(0);
        doc.setUploadBy(userId);
        doc.setScope("file");
        doc.setOwnerUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        documentMapper.insert(doc);
        uploadExecutor.submit(() -> processDocument(doc.getId(), path, ext, null));
        return doc.getId();
    }

    /** 文档解惑文件删除（B-31 #74）：向量置 NULL→逻辑删分块→逻辑删文档→清理落盘 */
    @org.springframework.transaction.annotation.Transactional
    public void deleteFileDocument(Long documentId) {
        chunkMapper.nullEmbeddingByDocument(documentId);
        chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId));
        documentMapper.deleteById(documentId);
    }

    // ==================== 重建 ====================

    public boolean isRebuilding() {
        return rebuilding.get();
    }

    /** 单文档重建（全局/个人通用——documentId 已定位，scope 天然明确） */
    public void rebuildDocument(Long documentId) {
        doRebuild(documentId);
    }

    /** 全量重建（管理端）：仅全局库（M15 scope 成对纪律） */
    public void rebuildAllGlobal() {
        doRebuild(null);
    }

    private void doRebuild(Long documentId) {
        if (!rebuilding.compareAndSet(false, true)) {
            throw BizException.badRequest("索引正在重建中，请稍后再试");
        }
        try {
            // M15：全量重建（documentId=null）只作用于全局库；单文档按 id 定位
            String scope = documentId == null ? "global" : null;
            int cleared = chunkMapper.clearEmbeddings(documentId, scope);
            log.info("[Rebuild] 开始重建 documentId={}，作废旧向量 {} 条", documentId, cleared);

            long afterId = 0L;
            int backfilled = 0;
            while (true) {
                List<KbChunk> pending = chunkMapper.selectPendingEmbedding(documentId, scope, afterId, REBUILD_BATCH);
                if (pending.isEmpty()) {
                    break;
                }
                backfilled += embedBackfill(pending);
                afterId = pending.get(pending.size() - 1).getId();
            }
            log.info("[Rebuild] 重建完成 documentId={}，回填向量 {} 条", documentId, backfilled);
        } finally {
            rebuilding.set(false);
        }
    }

    /** 单批回填：embedding 失败按退避重试，最终失败整批跳过（向量留 NULL），返回成功条数 */
    private int embedBackfill(List<KbChunk> chunks) {
        List<String> texts = chunks.stream()
                .map(c -> c.getContent() == null ? "" : c.getContent())
                .toList();
        List<float[]> vectors = null;
        for (int attempt = 1; attempt <= MAX_EMBED_ATTEMPTS; attempt++) {
            try {
                vectors = ingestor.embedBatch(texts);
                sleepQuietly(embedDelayMs);
                break;
            } catch (Exception e) {
                log.warn("[Rebuild] 批量向量化失败（{} 条，第 {} 次）：{}", chunks.size(), attempt, e.getMessage());
                sleepQuietly(embedDelayMs * attempt);
            }
        }
        if (vectors == null || vectors.size() != chunks.size()) {
            log.error("[Rebuild] 该批 {} 条向量化最终失败，已跳过（向量留空待下次重建）", chunks.size());
            return 0;
        }
        int ok = 0;
        for (int i = 0; i < chunks.size(); i++) {
            ok += chunkMapper.updateEmbedding(chunks.get(i).getId(), toPgVector(vectors.get(i)));
        }
        sleepQuietly(embedDelayMs);
        return ok;
    }

    private static String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
