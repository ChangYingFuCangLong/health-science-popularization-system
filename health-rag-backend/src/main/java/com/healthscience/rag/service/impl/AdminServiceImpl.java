package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.entity.KbChunk;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.entity.MessageFeedback;
import com.healthscience.rag.entity.SysUser;
import com.healthscience.rag.mapper.AdminMapper;
import com.healthscience.rag.mapper.KbChunkMapper;
import com.healthscience.rag.mapper.KbDocumentMapper;
import com.healthscience.rag.mapper.MessageFeedbackMapper;
import com.healthscience.rag.mapper.SysUserMapper;
import com.healthscience.rag.model.dto.AdminDTO;
import com.healthscience.rag.service.AdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理端实现（§6）。
 *
 * <p><b>M15 起的职责收缩</b>：上传流水线与重建迁往 {@link DocumentPipelineService}
 * （全局库/个人库共用，09 §八 决策 2），本类只保留管理端查询与维护动作，并新增：
 * <ul>
 *   <li><b>scope 守卫</b>：文档列表/看板/分块查看/删除只作用于全局库——
 *       用户个人库内容对管理端不可见（07 决策 D3，隐私优先）；</li>
 *   <li><b>删除事务化</b>：级联删除（向量作废→分块→文档）补 {@code @Transactional}
 *       （测试报告遗留 P1，个人库删除频率高，顺带修掉）。</li>
 * </ul>
 *
 * <p>日志与反馈的分页目前在内存里做 —— 这两张表的数据量在毕设规模下是千级以内，
 * 简单可靠优先；若后续数据量增长，再改为 SQL 层 LIMIT + 独立 count。
 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AdminServiceImpl implements AdminService {

    private final AdminMapper adminMapper;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final SysUserMapper userMapper;
    private final MessageFeedbackMapper feedbackMapper;
    private final com.healthscience.rag.mapper.ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper;
    private final DocumentPipelineService pipeline;

    /** 重建与元信息展示共用的向量模型（框架无关接口，C1/C2） */
    private final com.healthscience.rag.ai.EmbeddingService embeddingService;

    /** 向量维度展示值：配置声明（不实时探测，Ollama 离线时元信息接口仍可用） */
    @Value("${health.rag.embedding-dimension:1024}")
    private int embeddingDimension;

    @Override
    public PageResult<KbDocument> documents(Integer status, String category, String keyword,
                                            int pageNum, int pageSize) {
        Page<KbDocument> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100));
        page.addOrder(OrderItem.desc("id"));
        LambdaQueryWrapper<KbDocument> q = new LambdaQueryWrapper<>();
        // M15：管理端只见全局库——用户个人文档对管理端不可见（07 决策 D3）
        q.eq(KbDocument::getScope, KbDocument.SCOPE_GLOBAL);
        if (status != null) {
            q.eq(KbDocument::getStatus, status);
        }
        if (StringUtils.hasText(category)) {
            q.eq(KbDocument::getCategory, category.trim());
        }
        if (StringUtils.hasText(keyword)) {
            q.like(KbDocument::getTitle, keyword.trim());
        }
        return PageResult.of(documentMapper.selectPage(page, q));
    }

    @Override
    public List<AdminDTO.ChunkVO> documentChunks(Long documentId) {
        requireGlobalDocument(documentId);
        List<KbChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getDocumentId, documentId)
                        .orderByAsc(KbChunk::getChunkIndex));
        List<AdminDTO.ChunkVO> vos = new ArrayList<>(chunks.size());
        for (KbChunk c : chunks) {
            vos.add(new AdminDTO.ChunkVO(
                    c.getId(), c.getChunkIndex(), c.getSection(), c.getContent(), c.getCharCount(),
                    parseMeta(c.getMetaJson())));
        }
        return vos;
    }

    /** 管理端分块查看的 scope 守卫：个人文档一律 404（不暴露存在性） */
    private void requireGlobalDocument(Long documentId) {
        KbDocument doc = documentMapper.selectById(documentId);
        if (doc == null || !KbDocument.SCOPE_GLOBAL.equals(doc.getScope())) {
            throw BizException.notFound("文档不存在");
        }
    }

    /**
     * 向量索引元信息：真值实时推导（04 S3），<b>只统计全局库</b>（M15）。
     * 就绪 = 全局库非空且无待向量化分块；重建中优先展示为构建中。
     */
    @Override
    public AdminDTO.VectorMetaVO vectorMeta() {
        Map<String, Object> stats = chunkMapper.selectVectorStats("global");
        int total = intOf(stats.get("total"));
        int pending = intOf(stats.get("pending"));
        int status = pipeline.isRebuilding() ? 2 : (total > 0 && pending == 0 ? 1 : 0);
        return new AdminDTO.VectorMetaVO(embeddingService.modelName(), embeddingDimension, total, status, null);
    }

    @Override
    public Map<String, Object> retrievalTrace(Long messageId) {
        com.healthscience.rag.entity.ChatMessage message = chatMessageMapper.selectById(messageId);
        if (message == null || !com.healthscience.rag.entity.ChatMessage.ROLE_ASSISTANT.equals(message.getRole())
                || message.getTraceJson() == null || message.getTraceJson().isBlank()) {
            // 契约 §6.10：仅在助手消息上有效；无 trace（纯对话/历史消息）同为 404
            throw BizException.notFound("该消息没有检索过程记录");
        }
        try {
            Map<String, Object> trace = objectMapper.readValue(message.getTraceJson(), Map.class);
            // 契约示例含 messageId：落库时主键未知，读取侧补齐
            trace.put("messageId", messageId);
            return trace;
        } catch (Exception e) {
            throw BizException.notFound("该消息没有检索过程记录");
        }
    }

    @Override
    public AdminDTO.OverviewVO overview() {
        Map<String, Object> row = adminMapper.selectOverview();
        List<AdminDTO.TrendPoint> trend = new ArrayList<>();
        for (Map<String, Object> t : adminMapper.selectTrend()) {
            trend.add(new AdminDTO.TrendPoint(String.valueOf(t.get("date")), intOf(t.get("count"))));
        }
        List<AdminDTO.HotQuestion> hot = new ArrayList<>();
        for (Map<String, Object> h : adminMapper.selectHotQuestions(10)) {
            hot.add(new AdminDTO.HotQuestion(String.valueOf(h.get("question")), intOf(h.get("count"))));
        }
        return new AdminDTO.OverviewVO(
                intOf(row.get("totalUsers")),
                intOf(row.get("totalSessions")),
                intOf(row.get("totalMessages")),
                intOf(row.get("todayMessages")),
                row.get("rejectRate") == null ? 0.0 : ((BigDecimal) row.get("rejectRate")).doubleValue(),
                intOf(row.get("avgLatencyMs")),
                intOf(row.get("avgFirstTokenMs")),
                intOf(row.get("docCount")),
                intOf(row.get("chunkCount")),
                trend, hot);
    }

    @Override
    public PageResult<AdminDTO.ChatLogVO> chatLogs(Long userId, Integer isRejected,
                                                   String startDate, String endDate,
                                                   int pageNum, int pageSize) {
        // 契约口径 0/1，列已 BOOLEAN 化：mapper 边界转换
        Boolean rejected = isRejected == null ? null : isRejected != 0;
        // P3-3：非法日期字符串在 PG 参数转换处会 500，入口先校验为 400
        String start = validateDate(startDate, "startDate");
        String end = validateDate(endDate, "endDate");
        List<Map<String, Object>> rows =
                adminMapper.selectChatLogs(userId, rejected, start, end);
        List<AdminDTO.ChatLogVO> logs = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            logs.add(new AdminDTO.ChatLogVO(
                    longOf(r.get("messageId")), longOf(r.get("sessionId")), longOf(r.get("userId")),
                    str(r.get("username")), str(r.get("question")), str(r.get("answer")),
                    str(r.get("queryText")),
                    str(r.get("retrieveMode")), intOf(r.get("latencyMs")),
                    intOf(r.get("isRejected")), intOf(r.get("finishStatus")),
                    dateTime(r.get("createTime"))));
        }
        return paging(logs, pageNum, pageSize);
    }

    @Override
    public PageResult<AdminDTO.FeedbackVO> feedbacks(Integer status, int pageNum, int pageSize) {
        List<Map<String, Object>> rows = adminMapper.selectFeedbacks(status);
        List<AdminDTO.FeedbackVO> vos = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            vos.add(new AdminDTO.FeedbackVO(
                    longOf(r.get("id")), longOf(r.get("messageId")), longOf(r.get("userId")),
                    str(r.get("username")), str(r.get("type")), str(r.get("comment")),
                    intOf(r.get("status")), str(r.get("answerDigest")),
                    dateTime(r.get("createTime"))));
        }
        return paging(vos, pageNum, pageSize);
    }

    @Override
    public PageResult<AdminDTO.UserVO> users(int pageNum, int pageSize, String keyword) {
        Page<SysUser> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100));
        page.addOrder(OrderItem.asc("id"));
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        // F1：用户名或昵称模糊匹配。like 走 MyBatis-Plus 参数绑定（%kw% 作为参数传入），
        // 不做字符串拼接，无需额外转义；keyword 为空白时等价于无过滤
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(SysUser::getUsername, kw).or().like(SysUser::getNickname, kw));
        }
        return PageResult.of(userMapper.selectPage(page, qw),
                u -> new AdminDTO.UserVO(
                        u.getId(), u.getUsername(), u.getNickname(), u.getEmail(),
                        u.getRole(), u.getStatus(), u.getLastLoginAt(), u.getCreateTime()));
    }

    @Override
    public void markFeedbackHandled(Long feedbackId, Integer status, Long operatorId) {
        if (status == null || (status != 0 && status != 1)) {
            throw BizException.badRequest("状态只能是 0 待处理 / 1 已处理");
        }
        MessageFeedback fb = feedbackMapper.selectById(feedbackId);
        if (fb == null) {
            throw BizException.notFound("反馈不存在");
        }
        fb.setStatus(status);
        if (status == MessageFeedback.STATUS_HANDLED) {
            fb.setHandleBy(operatorId);
            fb.setHandleTime(LocalDateTime.now());
        } else {
            // 改回待处理：操作痕迹一并清空，避免留下"没人处理却显示已处理"的脏数据
            fb.setHandleBy(null);
            fb.setHandleTime(null);
        }
        feedbackMapper.updateById(fb);
    }

    @Override
    public void updateUserStatus(Long userId, Integer status, Long operatorId) {
        if (status == null || (status != 0 && status != 1)) {
            throw BizException.badRequest("状态只能是 0 禁用 / 1 正常");
        }
        if (userId.equals(operatorId) && status == 0) {
            // 否则管理员会把自己锁在外面，只能改数据库救回来
            throw BizException.badRequest("不能禁用当前登录的账号");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        user.setStatus(status);
        if (status == 0) {
            // 04-裁决 S5：禁用即踢下线 —— 与登出/改密共用 token_invalid_before 机制
            user.setTokenInvalidBefore(LocalDateTime.now());
        }
        userMapper.updateById(user);
    }

    /** 删除文档（仅全局库；个人文档由 owner 删除，D3）。级联补事务（遗留 P1） */
    @Override
    @Transactional
    public void deleteDocument(Long documentId) {
        requireGlobalDocument(documentId);
        // D9 删除级联：先作废向量（释放 pgvector 存储），再逻辑删分块与文档
        chunkMapper.nullEmbeddingByDocument(documentId);
        List<KbChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
        for (KbChunk c : chunks) {
            chunkMapper.deleteById(c.getId());
        }
        documentMapper.deleteById(documentId);
    }

    @Override
    public void rebuildAllVectors() {
        pipeline.rebuildAllGlobal();   // M15：全量重建只作用于全局库
    }

    @Override
    public void rebuildDocument(Long documentId) {
        requireGlobalDocument(documentId);
        pipeline.rebuildDocument(documentId);
    }

    @Override
    public boolean isRebuilding() {
        return pipeline.isRebuilding();
    }

    @Override
    public Long uploadDocument(String originalFilename, byte[] content,
                               String title, String category, String source, Long operatorId) {
        return pipeline.submitGlobal(originalFilename, content, title, category, source, operatorId);
    }

    @Override
    public AdminDTO.DocumentProgressVO documentProgress(Long documentId) {
        KbDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        long done = chunkMapper.selectCount(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
        Integer total = pipeline.processingTotal(documentId);
        if (doc.getStatus() != null && doc.getStatus() == KbDocument.STATUS_DONE) {
            total = doc.getChunkCount();
        }
        return new AdminDTO.DocumentProgressVO(documentId, doc.getStatus(),
                (int) done, total, doc.getFailReason());
    }

    // ---------- 工具 ----------

    /** 内存分页：数据量小时简单可靠 */
    private static <T> PageResult<T> paging(List<T> all, int pageNum, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), 100);
        return PageResult.of(all, Math.max(pageNum, 1), size);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMeta(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 日期参数校验：接受 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，非法 → 400 */
    private static String validateDate(String value, String name) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            if (value.length() == 10) {
                java.time.LocalDate.parse(value);
            } else {
                java.time.LocalDateTime.parse(value,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return value;
        } catch (java.time.format.DateTimeParseException e) {
            throw BizException.badRequest(name + " 格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
    }

    private static int intOf(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }

    private static Long longOf(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static LocalDateTime dateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime t) {
            return t;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        String s = String.valueOf(v);
        return LocalDateTime.parse(s.length() == 10
                ? LocalDate.parse(s).atStartOfDay().toString()
                : s.replace(' ', 'T'));
    }
}
