package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.healthscience.rag.ai.model.ChunkRef;
import com.healthscience.rag.ai.model.ChatTurn;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.entity.ChatMessage;
import com.healthscience.rag.entity.ChatSession;
import com.healthscience.rag.entity.MessageFeedback;
import com.healthscience.rag.mapper.ChatMessageMapper;
import com.healthscience.rag.mapper.ChatSessionMapper;
import com.healthscience.rag.mapper.MessageFeedbackMapper;
import com.healthscience.rag.model.dto.ChatDTO;
import com.healthscience.rag.service.ChatSessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 会话与消息业务实现，对应接口文档 §4.1–§4.5，并为 §4.6 提供落库支撑。
 *
 * <p><b>消息条数为什么不落库</b>：会话表刻意不设 {@code message_count} 冗余字段 ——
 * 流中断、重新生成都会让计数失真，列表页实时 COUNT 的代价在这个数据量下可以忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private static final String DEFAULT_TITLE = "新对话";
    private static final String HEALTH_DEFAULT_TITLE = "健康对话";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    /** 组装上下文时先取最近多少条，再按字符预算裁剪 */
    private static final int HISTORY_FETCH_LIMIT = 40;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final MessageFeedbackMapper feedbackMapper;
    private final ObjectMapper objectMapper;
    /** 建议组件在 provider 非 dashscope 时不装配；探测不到返回空列表（前端有本地派生兜底） */
    private final org.springframework.beans.factory.ObjectProvider<com.healthscience.rag.ai.SuggestionService> suggestionProvider;
    /** 画像服务（M16）：#58 前置校验用（hasContent） */
    private final com.healthscience.rag.service.UserPortraitService userPortraitService;

    @Override
    public ChatDTO.SessionVO create(Long userId, String title) {
        ChatSession session = newSession(userId, StringUtils.hasText(title) ? title.trim() : DEFAULT_TITLE);
        sessionMapper.insert(session);
        return new ChatDTO.SessionVO(session.getId(), session.getTitle(), 0,
                session.getCreateTime(), session.getUpdateTime());
    }

    @Override
    public List<ChatDTO.SessionVO> listHealthSessions(Long userId) {
        List<Map<String, Object>> rows = sessionMapper.selectHealthSessionList(userId, 0L, SESSION_LIST_MAX);
        List<ChatDTO.SessionVO> vos = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            vos.add(new ChatDTO.SessionVO(
                    toLong(row.get("id")),
                    (String) row.get("title"),
                    toInt(row.get("message_count")),
                    toDateTime(row.get("create_time")),
                    toDateTime(row.get("update_time"))));
        }
        return vos;
    }

    @Override
    public ChatDTO.SessionVO createHealthSession(Long userId, String title) {
        // 契约 #59：健康信息全空 → 2008（画像/指标/备忘三层，指标与备忘由 B-29/B-30 扩展，
        // 当前以画像为唯一健康信息源）
        if (!userPortraitService.hasContent(userId)) {
            throw new com.healthscience.rag.common.exception.BizException(
                    com.healthscience.rag.common.result.ErrorCode.PORTRAIT_NOT_FILLED,
                    "请先填写健康画像，再创建健康对话");
        }
        ChatSession session = newSession(userId,
                StringUtils.hasText(title) ? title.trim() : HEALTH_DEFAULT_TITLE);
        session.setKind(ChatSession.KIND_HEALTH);
        sessionMapper.insert(session);
        return new ChatDTO.SessionVO(session.getId(), session.getTitle(), 0,
                session.getCreateTime(), session.getUpdateTime());
    }

    @Override
    public List<ChatDTO.SessionVO> list(Long userId) {
        List<Map<String, Object>> rows = sessionMapper.selectSessionList(userId, 0L, SESSION_LIST_MAX);
        List<ChatDTO.SessionVO> vos = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            vos.add(new ChatDTO.SessionVO(
                    toLong(row.get("id")),
                    (String) row.get("title"),
                    toInt(row.get("message_count")),
                    toDateTime(row.get("create_time")),
                    toDateTime(row.get("update_time"))));
        }
        return vos;
    }

    @Override
    public void rename(Long userId, Long sessionId, String title) {
        if (!StringUtils.hasText(title)) {
            throw BizException.badRequest("标题不能为空");
        }
        String newTitle = title.trim();
        if (newTitle.length() > 100) {
            throw BizException.badRequest("标题不能超过 100 字");
        }
        ChatSession session = requireOwned(userId, sessionId);
        session.setTitle(newTitle);
        session.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    public void delete(Long userId, Long sessionId) {
        ChatSession session = requireOwned(userId, sessionId);
        // SessionKindPolicy（13 号 §2.3）：file 会话必须走 #75 级联删文件，通用删除入口拒绝
        if ("file".equals(session.getKind())) {
            throw BizException.badRequest("文档解惑会话需在解惑页删除（将级联清理全部文件）");
        }
        doDelete(sessionId);
    }

    @Override
    public void deleteInternal(Long userId, Long sessionId) {
        // 仅供 #75：上层已级联清理文件与绑定，这里跳过 kind 守卫，直接逻辑删除会话+消息
        requireOwned(userId, sessionId);
        doDelete(sessionId);
    }

    private void doDelete(Long sessionId) {
        // 会话与消息均为 @TableLogic，这里是 UPDATE ... SET deleted=1，不是物理删除
        sessionMapper.deleteById(sessionId);
        messageMapper.deleteBySessionId(sessionId);
    }

    @Override
    public PageResult<ChatDTO.MessageVO> messages(Long userId, Long sessionId, int pageNum, int pageSize) {
        requireOwned(userId, sessionId);
        int size = Math.min(Math.max(pageSize, 1), MESSAGE_PAGE_MAX);
        Page<ChatMessage> page = new Page<>(Math.max(pageNum, 1), size);
        // 正序：接口文档 §4.5 约定 records 按时间正序，便于前端直接追加渲染
        page.addOrder(OrderItem.asc("id"));
        Page<ChatMessage> result = messageMapper.selectPage(page,
                new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId));
        return PageResult.of(result, this::toMessageVO);
    }

    @Override
    public ChatSession resolveOrCreate(Long userId, Long sessionId, String question) {
        if (sessionId != null) {
            return requireOwned(userId, sessionId);
        }
        String title = DEFAULT_TITLE;
        if (StringUtils.hasText(question)) {
            String q = question.trim();
            title = q.length() <= TITLE_MAX_LEN ? q : q.substring(0, TITLE_MAX_LEN) + "…";
        }
        ChatSession session = newSession(userId, title);
        sessionMapper.insert(session);
        return session;
    }

    @Override
    public ChatMessage saveUserMessage(Long userId, Long sessionId, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(ROLE_USER);
        message.setContent(content);
        LocalDateTime now = LocalDateTime.now();
        message.setCreateTime(now);
        message.setUpdateTime(now);
        messageMapper.insert(message);
        return message;
    }

    @Override
    public ChatDTO.RetryContext prepareRetry(Long userId, Long sessionId, Long retryFromMessageId) {
        ChatMessage target = messageMapper.selectById(retryFromMessageId);
        // 逻辑删除的行会被 MyBatis-Plus 自动过滤，查不到即视为不存在。
        // 只允许对助手消息重新生成：传用户消息 ID 也能删的话，提问会被误删
        if (target == null || !sessionId.equals(target.getSessionId())
                || !userId.equals(target.getUserId())
                || !ROLE_ASSISTANT.equals(target.getRole())) {
            throw BizException.forbidden("目标消息不存在或无权限");
        }

        // B-27 整组重跑：目标属于对照解答组 → 整组逻辑删除（不允许组内一条新一条旧）
        if (target.getConflictGroupId() != null) {
            messageMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>()
                            .eq(ChatMessage::getConflictGroupId, target.getConflictGroupId())
                            .eq(ChatMessage::getSessionId, sessionId)
                            .set(ChatMessage::getDeleted, true));
        }

        // 先确认原问题存在，再删除目标消息：顺序反了会出现
        // 「找不到原问题却已把回答误删」的不可逆结果
        List<ChatMessage> prior = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getRole, ROLE_USER)
                        .lt(ChatMessage::getId, retryFromMessageId)
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT 1"));
        if (prior.isEmpty()) {
            throw BizException.badRequest("未找到该回答对应的提问");
        }
        messageMapper.deleteById(retryFromMessageId);

        ChatMessage origin = prior.get(0);
        return new ChatDTO.RetryContext(origin.getContent(), origin.getId());
    }

    @Override
    public List<ChatTurn> buildHistory(Long sessionId, int maxChars, Long beforeMessageId) {
        LambdaQueryWrapper<ChatMessage> q = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByDesc(ChatMessage::getId);
        if (beforeMessageId != null) {
            // 重新生成：只要原问题之前的内容，避免原问题与本轮提问重复
            q.lt(ChatMessage::getId, beforeMessageId);
        }
        q.last("LIMIT " + HISTORY_FETCH_LIMIT);

        LinkedList<ChatTurn> turns = new LinkedList<>();
        int budget = maxChars;
        for (ChatMessage m : messageMapper.selectList(q)) {   // 结果按 id 倒序 = 从最新往前
            int len = m.getContent() == null ? 0 : m.getContent().length();
            // 至少保留一轮：首条即便超预算也放入，避免上下文直接为空
            if (budget - len < 0 && !turns.isEmpty()) {
                break;
            }
            budget -= len;
            turns.addFirst(new ChatTurn(m.getRole(), m.getContent()));
        }
        return turns;   // addFirst 后即为正序
    }

    @Override
    public ChatMessage saveAssistantMessage(Long userId,
                                            Long sessionId,
                                            String content,
                                            Integer latencyMs,
                                            Integer firstTokenMs,
                                            Integer finishStatus,
                                            String retrieveMode,
                                            String queryText,
                                            boolean isRejected,
                                            List<ChunkRef> references,
                                            String answerSource,
                                            String conflictGroupId) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(ROLE_ASSISTANT);
        message.setContent(content);
        message.setQueryText(StringUtils.hasText(queryText) ? queryText : null);
        message.setLatencyMs(latencyMs);
        message.setFirstTokenMs(firstTokenMs);
        message.setFinishStatus(finishStatus);
        message.setRetrieveMode(retrieveMode);
        message.setIsRejected(isRejected);
        message.setAnswerSource(answerSource);
        message.setConflictGroupId(conflictGroupId);
        message.setReferencesJson(writeJson(references));
        LocalDateTime now = LocalDateTime.now();
        message.setCreateTime(now);
        message.setUpdateTime(now);
        messageMapper.insert(message);
        return message;
    }

    @Override
    public void touch(Long sessionId) {
        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    public void addFeedback(Long userId, Long messageId, String type, String comment) {
        if (!MessageFeedback.TYPE_USEFUL.equals(type)
                && !MessageFeedback.TYPE_USELESS.equals(type)
                && !MessageFeedback.TYPE_WRONG.equals(type)) {
            throw BizException.badRequest("反馈类型只能是 useful / useless / wrong");
        }
        if (comment != null && comment.length() > 500) {
            throw BizException.badRequest("补充说明不能超过 500 字");
        }
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null || !userId.equals(message.getUserId())) {
            throw BizException.forbidden("消息不存在或无权限");
        }

        MessageFeedback exist = feedbackMapper.selectOne(
                new LambdaQueryWrapper<MessageFeedback>()
                        .eq(MessageFeedback::getMessageId, messageId)
                        .eq(MessageFeedback::getUserId, userId));
        if (exist != null) {
            // 同一用户改主意时覆盖，避免产生互相矛盾的多条记录
            exist.setType(type);
            exist.setComment(comment);
            exist.setStatus(MessageFeedback.STATUS_PENDING);
            feedbackMapper.updateById(exist);
            return;
        }
        MessageFeedback fb = new MessageFeedback();
        fb.setMessageId(messageId);
        fb.setUserId(userId);
        fb.setType(type);
        fb.setComment(comment);
        fb.setStatus(MessageFeedback.STATUS_PENDING);
        feedbackMapper.insert(fb);
    }

    @Override
    public void saveTrace(Long messageId, Map<String, Object> trace) {
        if (trace == null || trace.isEmpty() || messageId == null) {
            return;
        }
        try {
            messageMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>()
                            .eq(ChatMessage::getId, messageId)
                            .set(ChatMessage::getTraceJson, objectMapper.writeValueAsString(trace)));
        } catch (JsonProcessingException e) {
            log.warn("trace 序列化失败，跳过回放落库 messageId={}", messageId, e);
        }
    }

    @Override
    public List<String> suggestions(Long userId, Long messageId) {
        ChatMessage message = messageMapper.selectById(messageId);
        // 契约 02 §4.9：不存在 / 非本人 / 非助手消息 → 404
        if (message == null || !userId.equals(message.getUserId())
                || !ROLE_ASSISTANT.equals(message.getRole())) {
            throw BizException.notFound("消息不存在");
        }
        List<String> cached = parseSuggestions(message.getSuggestionsJson());
        if (!cached.isEmpty()) {
            return cached;   // 首次生成后直达缓存：演示每次点都秒出，且建议可审计
        }
        com.healthscience.rag.ai.SuggestionService svc = suggestionProvider.getIfAvailable();
        if (svc == null) {
            return List.of();
        }
        List<String> titles = parseRefs(message.getReferencesJson()).stream()
                .map(com.healthscience.rag.ai.model.ChunkRef::doc)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .limit(5)
                .toList();
        List<String> generated = svc.suggest(message.getContent(), titles);
        if (!generated.isEmpty()) {
            messageMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>()
                            .eq(ChatMessage::getId, messageId)
                            .set(ChatMessage::getSuggestionsJson, writeListJson(generated)));
        }
        return generated;
    }

    /** 序列化字符串数组（建议缓存）；失败按空数组入库 */
    private String writeListJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("建议缓存序列化失败，按空数组入库", e);
            return "[]";
        }
    }

    /** 反序列化建议缓存；为空或脏数据返回空列表（不缓存失败结果） */
    private List<String> parseSuggestions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("[ChatSession] 建议缓存解析失败，按空处理", e);
            return List.of();
        }
    }

    private ChatSession newSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setKind(ChatSession.KIND_NORMAL);   // 普通会话；专属对话只能走 #58 单入口
        session.setTitle(title);
        LocalDateTime now = LocalDateTime.now();
        session.setCreateTime(now);
        session.setUpdateTime(now);
        return session;
    }

    private ChatSession requireOwned(Long userId, Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        // P3-1：不存在（含本人已删除）与「别人的会话」语义分离——前者 404，后者 403
        if (session == null) {
            throw BizException.notFound("会话不存在");
        }
        if (!userId.equals(session.getUserId())) {
            throw BizException.forbidden("会话不存在或无权限");
        }
        return session;
    }

    private ChatDTO.MessageVO toMessageVO(ChatMessage m) {
        // 字段一律返回实际值，不按角色返回 null：
        // 用户消息虽无"耗时/完成状态"的语义，但接口契约不区分角色，
        // 前端按统一结构渲染；返回 null 会逼着前端到处判空。
        // 用户消息的这些列取表默认值（finish_status=1、耗时 0），语义上也自洽。
        return new ChatDTO.MessageVO(
                m.getId(),
                m.getRole(),
                m.getContent(),
                parseRefs(m.getReferencesJson()),
                m.getRetrieveMode() == null ? RETRIEVE_MODE_NONE : m.getRetrieveMode(),
                m.getLatencyMs(),
                m.getFirstTokenMs(),
                m.getFinishStatus(),
                // 契约（02 §4.5）对外仍是 0/1：PG 列已 BOOLEAN 化，边界处收敛
                Boolean.TRUE.equals(m.getIsRejected()) ? 1 : 0,
                m.getCreateTime());
    }

    /** 反序列化引用片段；为空或解析失败时返回空列表，不让单条脏数据影响整页渲染 */
    private List<ChunkRef> parseRefs(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            List<ChunkRef> refs = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ChunkRef.class));
            return refs == null ? List.of() : refs;
        } catch (Exception e) {
            log.warn("[ChatSession] 引用片段解析失败，按空处理", e);
            return List.of();
        }
    }

    private String writeJson(List<ChunkRef> references) {
        if (references == null || references.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(references);
        } catch (JsonProcessingException e) {
            log.warn("引用片段序列化失败，按空数组入库", e);
            return "[]";
        }
    }

    /** Mapper 用 Map 承载聚合结果，此处统一收敛类型，避免调用方各自判空 */
    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static Integer toInt(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static LocalDateTime toDateTime(Object v) {
        if (v instanceof LocalDateTime t) {
            return t;
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        }
        return null;
    }
}
