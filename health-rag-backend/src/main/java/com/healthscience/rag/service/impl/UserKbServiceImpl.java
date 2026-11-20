package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.entity.KbChunk;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.entity.UserKbSetting;
import com.healthscience.rag.mapper.KbChunkMapper;
import com.healthscience.rag.mapper.KbDocumentMapper;
import com.healthscience.rag.mapper.UserKbSettingMapper;
import com.healthscience.rag.model.dto.UserKbDTO;
import com.healthscience.rag.service.UserKbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户个人知识库实现（M15，契约 07）。
 *
 * <p><b>复用</b>：RAG 处理链路（Tika 抽取/分块/向量化/状态机）完全复用
 * {@link DocumentPipelineService}，差异只在归属写入、落盘目录与配额前置（07 §六）。
 * <b>隔离</b>：所有查询带 owner 条件；越权一律 404。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserKbServiceImpl implements UserKbService {

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final UserKbSettingMapper settingMapper;
    private final DocumentPipelineService pipeline;

    @Value("${health.rag.upload-dir:./data/uploads}")
    private String uploadDir;

    @Value("${health.rag.user-kb.max-documents:20}")
    private int maxDocuments;

    @Value("${health.rag.user-kb.max-total-size-mb:100}")
    private int maxTotalSizeMb;

    @Value("${health.rag.user-kb.max-total-chunks:3000}")
    private int maxTotalChunks;

    // ==================== #44 概览 ====================

    @Override
    public UserKbDTO.StatsVO stats(Long userId) {
        List<KbDocument> docs = documentMapper.selectList(ownerDocs(userId));
        int ready = 0, processing = 0, failed = 0;
        long totalSize = 0;
        for (KbDocument d : docs) {
            totalSize += d.getFileSize() == null ? 0 : d.getFileSize();
            if (d.getStatus() != null) {
                switch (d.getStatus()) {
                    case KbDocument.STATUS_DONE -> ready++;
                    case KbDocument.STATUS_PARSING, KbDocument.STATUS_PENDING -> processing++;
                    case KbDocument.STATUS_FAILED -> failed++;
                    default -> { }
                }
            }
        }
        Map<String, Object> chunkStats = chunkMapper.selectOwnerChunkStats(userId);
        int chunkCount = intOf(chunkStats.get("total"));
        int pendingChunks = intOf(chunkStats.get("pending"));
        return new UserKbDTO.StatsVO(
                docs.size(), chunkCount, totalSize, ready, processing, failed, pendingChunks,
                new UserKbDTO.QuotaVO(maxDocuments, (long) maxTotalSizeMb * 1024 * 1024,
                        maxTotalChunks, docs.size(), totalSize, chunkCount));
    }

    // ==================== #45 列表 ====================

    @Override
    public PageResult<UserKbDTO.DocumentVO> documents(Long userId, String keyword, Integer status,
                                                      int pageNum, int pageSize) {
        Page<KbDocument> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100));
        page.addOrder(OrderItem.desc("id"));
        LambdaQueryWrapper<KbDocument> q = ownerDocs(userId);
        if (StringUtils.hasText(keyword)) {
            q.like(KbDocument::getTitle, keyword.trim());
        }
        if (status != null) {
            q.eq(KbDocument::getStatus, status);
        }
        return PageResult.of(documentMapper.selectPage(page, q), this::toDocumentVO);
    }

    private UserKbDTO.DocumentVO toDocumentVO(KbDocument d) {
        return new UserKbDTO.DocumentVO(d.getId(), d.getTitle(), d.getFileType(),
                d.getFileSize(), d.getStatus(), d.getFailReason(), d.getChunkCount(), d.getCreateTime());
    }

    // ==================== #46 上传 ====================

    @Override
    public Long upload(Long userId, String originalFilename, byte[] content, String title) {
        // 配额前置（07 §5.3 #46：校验在落盘之前，避免无效文件占用磁盘）
        Long usedDocs = documentMapper.selectCount(ownerDocs(userId));
        if (usedDocs != null && usedDocs >= maxDocuments) {
            throw new BizException(ErrorCode.KB_QUOTA_EXCEEDED,
                    "个人库文档数已达上限 " + maxDocuments + " 篇");
        }
        long usedSize = docsTotalSize(userId);
        long maxSize = (long) maxTotalSizeMb * 1024 * 1024;
        if (content != null && usedSize + content.length > maxSize) {
            throw new BizException(ErrorCode.KB_QUOTA_EXCEEDED,
                    "个人库总占用将超过 " + maxTotalSizeMb + " MB 上限");
        }
        // 总块数配额在处理中校验（块数要分块后才知道）：剩余配额透传给流水线
        Map<String, Object> chunkStats = chunkMapper.selectOwnerChunkStats(userId);
        int usedChunks = intOf(chunkStats.get("total"));
        int chunkQuota = Math.max(0, maxTotalChunks - usedChunks);
        return pipeline.submitPersonal(userId, originalFilename, content, title, chunkQuota);
    }

    // ==================== #47 进度 / #48 分块预览 ====================

    @Override
    public UserKbDTO.ProgressVO progress(Long userId, Long documentId) {
        KbDocument doc = requireOwned(userId, documentId);
        long done = chunkMapper.selectCount(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
        Integer total = pipeline.processingTotal(documentId);
        if (doc.getStatus() != null && doc.getStatus() == KbDocument.STATUS_DONE) {
            total = doc.getChunkCount();
        }
        return new UserKbDTO.ProgressVO(documentId, doc.getStatus(),
                (int) done, total, doc.getFailReason());
    }

    @Override
    public PageResult<UserKbDTO.ChunkPreviewVO> chunks(Long userId, Long documentId,
                                                       int pageNum, int pageSize) {
        requireOwned(userId, documentId);
        Page<KbChunk> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100));
        page.addOrder(OrderItem.asc("chunk_index"));
        Page<KbChunk> result = chunkMapper.selectPage(page,
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
        return PageResult.of(result, c -> new UserKbDTO.ChunkPreviewVO(
                c.getId(), c.getChunkIndex(), c.getSection(), c.getContent(), c.getCharCount()));
    }

    // ==================== #49 删除（事务化，顺带修全局库遗留 P1） ====================

    @Override
    @Transactional
    public void delete(Long userId, Long documentId) {
        KbDocument doc = requireOwned(userId, documentId);
        // 级联同全局库（D9）：作废向量 → 逻辑删分块 → 逻辑删文档
        chunkMapper.nullEmbeddingByDocument(documentId);
        List<KbChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
        for (KbChunk c : chunks) {
            chunkMapper.deleteById(c.getId());
        }
        documentMapper.deleteById(documentId);
        // 个人库额外要求（07 §5.3 #49）：清理落盘文件，用户删除即真正释放磁盘
        deleteStoredFile(userId, doc.getFilePath());
    }

    private void deleteStoredFile(Long userId, String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(uploadDir, "user-kb", String.valueOf(userId), storedName);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            // 文件清理失败不影响删除语义：记录日志，磁盘由运维清理
            log.warn("[UserKb] 落盘文件清理失败 userId={} file={}: {}", userId, storedName, e.getMessage());
        }
    }

    // ==================== #50 重建 ====================

    @Override
    public void rebuild(Long userId, Long documentId) {
        requireOwned(userId, documentId);
        // 与全局重建共用互斥（Ollama 单实例串行瓶颈，07 §八）：冲突 → 400
        pipeline.rebuildDocument(documentId);
    }

    // ==================== #51/#52 偏好 ====================

    @Override
    public UserKbDTO.SettingsVO settings(Long userId) {
        UserKbSetting s = settingMapper.selectById(userId);
        boolean include = s == null || s.getIncludeInChat() == null || s.getIncludeInChat();
        return new UserKbDTO.SettingsVO(hasContent(userId), include);
    }

    @Override
    public void updateSettings(Long userId, UserKbDTO.SettingsUpdateRequest request) {
        UserKbSetting setting = settingMapper.selectById(userId);
        if (setting == null) {
            setting = new UserKbSetting();
            setting.setUserId(userId);
            setting.setIncludeInChat(request.includeInChat() == null || request.includeInChat());
            LocalDateTime now = LocalDateTime.now();
            setting.setCreateTime(now);
            setting.setUpdateTime(now);
            try {
                settingMapper.insert(setting);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                setting = settingMapper.selectById(userId);   // 并发首插：转更新
                if (setting == null) {
                    throw e;
                }
            }
        }
        setting.setIncludeInChat(request.includeInChat() == null ? setting.getIncludeInChat() : request.includeInChat());
        setting.setUpdateTime(LocalDateTime.now());
        settingMapper.updateById(setting);
    }

    // ==================== 问答链路支撑 ====================

    @Override
    public boolean hasContent(Long userId) {
        Long n = documentMapper.selectCount(ownerDocs(userId)
                .eq(KbDocument::getStatus, KbDocument.STATUS_DONE));
        return n != null && n > 0;
    }

    @Override
    public boolean includeInChat(Long userId) {
        UserKbSetting s = settingMapper.selectById(userId);
        return s == null || s.getIncludeInChat() == null || s.getIncludeInChat();
    }

    // ==================== 工具 ====================

    private LambdaQueryWrapper<KbDocument> ownerDocs(Long userId) {
        return new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getOwnerUserId, userId)
                .eq(KbDocument::getScope, KbDocument.SCOPE_PERSONAL);
    }

    /** owner 校验：他人文档与不存在的文档一律 404（不暴露存在性，07 §5.1） */
    private KbDocument requireOwned(Long userId, Long documentId) {
        KbDocument doc = documentMapper.selectById(documentId);
        if (doc == null || !userId.equals(doc.getOwnerUserId())
                || !KbDocument.SCOPE_PERSONAL.equals(doc.getScope())) {
            throw BizException.notFound("文档不存在");
        }
        return doc;
    }

    private long docsTotalSize(Long userId) {
        List<KbDocument> docs = documentMapper.selectList(ownerDocs(userId));
        return docs.stream().mapToLong(d -> d.getFileSize() == null ? 0 : d.getFileSize()).sum();
    }

    private static int intOf(Object v) {
        if (v == null) {
            return 0;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }
}
