package com.healthscience.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.ai.RetrievalService;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.entity.ChatSession;
import com.healthscience.rag.entity.QaSessionDocument;
import com.healthscience.rag.mapper.ChatSessionMapper;
import com.healthscience.rag.mapper.QaSessionDocumentMapper;
import com.healthscience.rag.model.dto.ChatDTO;
import com.healthscience.rag.service.impl.DocumentPipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文档解惑接口（M16 v4.0，契约 12 号，#69–#75）：会话绑定文件集的多文件问答。
 *
 * <p>kind='file' 会话：上传文件到会话（可多次调用=动态追加，≤10 份/会话）；
 * 检索由 stream 按 sessionId 自动走文件集硬限定（<b>stream 不加参数</b>，12 号裁决）。
 */
@Tag(name = "文档解惑 /api/knowledge/qa")
@RestController
@RequestMapping("/api/knowledge/qa")
@RequiredArgsConstructor
public class QaSessionController {

    /** 单会话文件数上限（12 号） */
    private static final int MAX_FILES_PER_SESSION = 10;

    private final ChatSessionMapper sessionMapper;
    private final com.healthscience.rag.mapper.KbDocumentMapper documentMapper;
    private final QaSessionDocumentMapper qaDocMapper;
    private final DocumentPipelineService pipeline;
    private final RetrievalService retrievalService;
    private final com.healthscience.rag.service.ChatSessionService chatSessionService;

    // ---------- #69 创建解惑会话 ----------
    @PostMapping("/sessions")
    @Operation(summary = "创建文档解惑会话")
    public R<ChatDTO.SessionVO> create(@RequestBody(required = false) Map<String, String> body) {
        Long userId = UserContext.requireId();
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setKind("file");
        session.setTitle(body != null && body.get("title") != null && !body.get("title").isBlank()
                ? body.get("title").trim() : "文档解惑");
        LocalDateTime now = LocalDateTime.now();
        session.setCreateTime(now);
        session.setUpdateTime(now);
        sessionMapper.insert(session);
        return R.ok(new ChatDTO.SessionVO(session.getId(), session.getTitle(), 0,
                session.getCreateTime(), session.getUpdateTime()));
    }

    // ---------- #70 我的解惑列表（含文件数与处理状态汇总） ----------
    @GetMapping("/sessions")
    @Operation(summary = "解惑会话列表")
    public R<List<Map<String, Object>>> list() {
        Long userId = UserContext.requireId();
        List<ChatSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId).eq(ChatSession::getKind, "file")
                .orderByDesc(ChatSession::getUpdateTime));
        return R.ok(sessions.stream().map(s -> {
            List<Map<String, Object>> files = qaDocMapper.selectSessionFiles(s.getId());
            long processing = files.stream().filter(f -> Integer.valueOf(1).equals(num(f.get("status")))).count();
            long failed = files.stream().filter(f -> Integer.valueOf(3).equals(num(f.get("status")))).count();
            return Map.<String, Object>of(
                    "id", s.getId(),
                    "title", s.getTitle(),
                    "fileCount", files.size(),
                    "processingCount", processing,
                    "failedCount", failed,
                    "createTime", String.valueOf(s.getCreateTime()),
                    "updateTime", String.valueOf(s.getUpdateTime()));
        }).toList());
    }

    // ---------- #71 上传文件到会话（动态追加） ----------
    @PostMapping(value = "/sessions/{id}/documents",
                  consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文件到解惑会话")
    public R<Map<String, Object>> upload(@PathVariable Long id,
                                         @RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "title", required = false) String title) {
        Long userId = UserContext.requireId();
        requireOwned(userId, id);
        Long count = qaDocMapper.selectCount(new LambdaQueryWrapper<QaSessionDocument>()
                .eq(QaSessionDocument::getSessionId, id));
        if (count != null && count >= MAX_FILES_PER_SESSION) {
            throw new BizException(ErrorCode.KB_QUOTA_EXCEEDED,
                    "单个解惑会话最多 " + MAX_FILES_PER_SESSION + " 份文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.KB_FILE_INVALID, "文件读取失败");
        }
        Long docId = pipeline.submitFile(id, userId, file.getOriginalFilename(), bytes, title);
        QaSessionDocument bind = new QaSessionDocument();
        bind.setSessionId(id);
        bind.setDocumentId(docId);
        bind.setCreateTime(LocalDateTime.now());
        qaDocMapper.insert(bind);
        return R.ok(Map.of("documentId", docId, "status", 1));
    }

    // ---------- #72 会话文件列表 ----------
    @GetMapping("/sessions/{id}/documents")
    @Operation(summary = "解惑会话文件列表")
    public R<List<Map<String, Object>>> files(@PathVariable Long id) {
        requireOwned(UserContext.requireId(), id);
        return R.ok(qaDocMapper.selectSessionFiles(id));
    }

    // ---------- #73 单文件进度（复用个人库进度语义） ----------
    @GetMapping("/documents/{id}/progress")
    @Operation(summary = "解惑文件处理进度")
    public R<Map<String, Object>> progress(@PathVariable Long id) {
        var doc = requireDocOwned(UserContext.requireId(), id);
        Integer total = pipeline.processingTotal(id);
        if (Integer.valueOf(2).equals(doc.getStatus()) && doc.getChunkCount() != null) {
            total = doc.getChunkCount();
        }
        return R.ok(Map.of("status", doc.getStatus(), "total", total == null ? 0 : total,
                "failReason", doc.getFailReason() == null ? "" : doc.getFailReason()));
    }

    // ---------- #74 移除文件（级联分块+向量+落盘；会话保留可续传） ----------
    @DeleteMapping("/documents/{id}")
    @Operation(summary = "移除解惑文件")
    public R<Void> removeFile(@PathVariable Long id) {
        var doc = requireDocOwned(UserContext.requireId(), id);
        qaDocMapper.delete(new LambdaQueryWrapper<QaSessionDocument>()
                .eq(QaSessionDocument::getDocumentId, id));
        pipeline.deleteFileDocument(id);   // 分块向量置 NULL→逻辑删→落盘清理 kb-qa/
        return R.ok();
    }

    // ---------- #75 删除解惑会话（级联全部文件+消息） ----------
    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "删除解惑会话")
    public R<Void> delete(@PathVariable Long id) {
        Long userId = UserContext.requireId();
        requireOwned(userId, id);
        for (Map<String, Object> f : qaDocMapper.selectSessionFiles(id)) {
            Number docId = (Number) f.get("id");
            pipeline.deleteFileDocument(docId.longValue());
        }
        qaDocMapper.delete(new LambdaQueryWrapper<QaSessionDocument>()
                .eq(QaSessionDocument::getSessionId, id));
        chatSessionService.deleteInternal(userId, id);   // 逻辑删会话+联动消息（跳过 file 守卫）
        return R.ok();
    }

    // ---------- 工具 ----------

    private ChatSession requireOwned(Long userId, Long sessionId) {
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null || !userId.equals(s.getUserId()) || !"file".equals(s.getKind())) {
            throw BizException.notFound("解惑会话不存在");
        }
        return s;
    }

    private com.healthscience.rag.entity.KbDocument requireDocOwned(Long userId, Long documentId) {
        var doc = documentMapper.selectById(documentId);
        if (doc == null || !userId.equals(doc.getOwnerUserId()) || !"file".equals(doc.getScope())) {
            throw BizException.notFound("文件不存在");
        }
        return doc;
    }

    private static Integer num(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }
}
