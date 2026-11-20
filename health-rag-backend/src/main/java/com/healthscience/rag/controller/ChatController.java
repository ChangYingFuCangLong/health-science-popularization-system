package com.healthscience.rag.controller;

import com.healthscience.rag.ai.AsrService;
import com.healthscience.rag.ai.ChatService;
import com.healthscience.rag.ai.model.ChatChunk;
import com.healthscience.rag.ai.model.ChatRequest;
import com.healthscience.rag.ai.model.ChatTurn;
import com.healthscience.rag.ai.model.ChunkRef;
import com.healthscience.rag.ai.model.DoneInfo;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.common.result.R;
import com.healthscience.rag.entity.ChatMessage;
import com.healthscience.rag.entity.ChatSession;
import com.healthscience.rag.model.dto.ChatDTO;
import com.healthscience.rag.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.healthscience.rag.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话管理与流式问答，对应接口文档 §4.1–§4.6。
 *
 * <p><b>协议适配的唯一归属</b>：{@link ChatChunk} → SSE 事件的转换只发生在本类，
 * AI 层不感知传输协议（约束 C1/C2）。
 *
 * <p><b>M4 落库约定</b>：
 * <ul>
 *   <li>首次提问：落库用户消息 → 流式生成 → 结束时落库助手消息（{@code finishStatus=1}）</li>
 *   <li>重新生成（{@code retryFromMessageId}）：先逻辑删除原助手消息，
 *       <b>不重复落库用户消息</b>，基于同一问题重新生成</li>
 *   <li>流中断（客户端断开 / 出错）：保留已生成的部分内容，{@code finishStatus=0}</li>
 * </ul>
 *
 * <p><b>为什么 userId 在方法入口就取出</b>：{@link UserContext} 基于 ThreadLocal，
 * 而 SSE 的下游操作可能切换到别的线程，届时读到的会是 null。
 * 在请求线程上取一次并以局部变量捕获，可彻底避开ThreadLocal 的传播问题。
 */
@Slf4j
@Tag(name = "问答 /api/chat")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final int QUESTION_MAX_LEN = 1000;

    /** 普通会话上下文预算（02 §4.6 契约钉死）：会话间隔离是隐私边界，绝不放大 */
    private static final int HISTORY_MAX_CHARS = 3000;

    /** 健康会话「尽量不压缩」预算（08 §2.5）：9000 字/100 条，可配 */
    @org.springframework.beans.factory.annotation.Value("${health.rag.health-history-max-chars:9000}")
    private int healthHistoryMaxChars;

    @org.springframework.beans.factory.annotation.Value("${health.rag.health-history-fetch-limit:100}")
    private int healthHistoryFetchLimit;

    /** 服务端默认检索策略；客户端未在请求中指定时用它落库（兜底值与 application.yml 默认保持一致） */
    @org.springframework.beans.factory.annotation.Value("${health.rag.default-mode:hybrid}")
    private String defaultRetrieveMode;

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;
    /** 语音识别组件 provider 非 dashscope 时不装配；探测不到返回 2005（前端走 Web Speech 路径） */
    private final org.springframework.beans.factory.ObjectProvider<AsrService> asrProvider;
    private final com.healthscience.rag.service.UserPortraitService userPortraitService;   // M16 画像注入
    private final com.healthscience.rag.service.UserKbService userKbService;               // M15 kbScope/偏好
    private final com.healthscience.rag.service.HealthContextService healthContextService; // M16 v4.0 三层合并

    // ---------- §4.1 新建会话 ----------
    @PostMapping("/sessions")
    public R<ChatDTO.SessionVO> createSession(@RequestBody(required = false) ChatDTO.CreateSessionRequest body) {
        Long userId = UserContext.requireId();
        String title = body == null ? null : body.title();
        return R.ok(chatSessionService.create(userId, title));
    }

    // ---------- §4.2 会话列表 ----------
    @GetMapping("/sessions")
    public R<List<ChatDTO.SessionVO>> listSessions() {
        return R.ok(chatSessionService.list(UserContext.requireId()));
    }

    // ---------- §4.3 重命名会话 ----------
    @PutMapping("/sessions/{id}")
    public R<Void> renameSession(@PathVariable Long id, @RequestBody ChatDTO.RenameRequest body) {
        chatSessionService.rename(UserContext.requireId(), id, body == null ? null : body.title());
        return R.ok();
    }

    // ---------- §4.4 删除会话 ----------
    @DeleteMapping("/sessions/{id}")
    public R<Void> deleteSession(@PathVariable Long id) {
        chatSessionService.delete(UserContext.requireId(), id);
        return R.ok();
    }

    // ---------- §4.5 会话消息列表 ----------
    @GetMapping("/sessions/{id}/messages")
    public R<PageResult<ChatDTO.MessageVO>> listMessages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {
        return R.ok(chatSessionService.messages(UserContext.requireId(), id, pageNum, pageSize));
    }

    // ---------- §4.8 语音识别 ----------
    /** 上传类型白名单（契约 02 §4.8）；MIME 可能带 codecs 参数，取分号前主类型比对 */
    private static final Set<String> AUDIO_TYPES = Set.of(
            "audio/webm", "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4",
            "audio/ogg", "audio/opus", "audio/aac");
    private static final long AUDIO_MAX_BYTES = 15L * 1024 * 1024;

    @PostMapping(value = "/asr", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> asr(@RequestParam("audio") MultipartFile audio) {
        UserContext.requireId();
        AsrService asr = asrProvider.getIfAvailable();
        if (asr == null) {
            throw new BizException(ErrorCode.ASR_FAILED, "语音识别未启用");
        }
        if (audio == null || audio.isEmpty()) {
            throw new BizException(ErrorCode.ASR_FAILED, "音频为空");
        }
        if (audio.getSize() > AUDIO_MAX_BYTES) {
            throw new BizException(ErrorCode.ASR_FAILED, "音频超过 15MB 上限");
        }
        String type = audio.getContentType() == null ? "" :
                audio.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!AUDIO_TYPES.contains(type)) {
            throw new BizException(ErrorCode.ASR_FAILED, "不支持的音频格式: " + type);
        }
        // 音频只在内存中传递，不落盘（04-裁决隐私红线）
        byte[] data;
        try {
            data = audio.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.ASR_FAILED, "音频读取失败");
        }
        AsrService.AsrResult result = asr.transcribe(data, type);
        return R.ok(Map.of("text", result.text(), "durationMs", result.audioSeconds() * 1000L));
    }

    // ---------- §4.9 追问建议 ----------
    @GetMapping("/messages/{messageId}/suggestions")
    public R<ChatDTO.SuggestionsVO> suggestions(@PathVariable Long messageId) {
        return R.ok(new ChatDTO.SuggestionsVO(
                chatSessionService.suggestions(UserContext.requireId(), messageId)));
    }

    // ---------- §5.4 #57/#58 专属对话（M16） ----------
    /** #57 查询专属对话（不存在 → data null，非 404——"还没有"是正常状态） */
    // ---------- §6.4 #58/#59 健康对话（M16 v4.0，仅供个人健康科普模块） ----------
    /** #58 健康对话列表（kind=health，可多条，updateTime 倒序） */
    @GetMapping("/sessions/health")
    @Operation(summary = "健康对话列表")
    public R<List<ChatDTO.SessionVO>> listHealthSessions(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {
        Long userId = UserContext.requireId();
        List<ChatDTO.SessionVO> all = chatSessionService.listHealthSessions(userId);
        int from = (int) Math.min((long) Math.max(pageNum - 1, 0) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return R.ok(all.subList(from, to));
    }

    /** #59 创建健康对话（可多条；健康信息全空 → 2008） */
    @PostMapping("/sessions/health")
    @Operation(summary = "创建健康对话")
    public R<ChatDTO.SessionVO> createHealthSession(
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            java.util.Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        return R.ok(chatSessionService.createHealthSession(UserContext.requireId(), title));
    }

    // ---------- §4.7 消息反馈 ----------
    @PostMapping("/messages/{id}/feedback")
    public R<Void> feedback(@PathVariable Long id, @RequestBody FeedbackRequest body) {
        Long userId = UserContext.requireId();
        if (body == null || body.type() == null) {
            throw BizException.badRequest("反馈类型不能为空");
        }
        chatSessionService.addFeedback(userId, id, body.type(), body.comment());
        return R.ok();
    }

    // ---------- §4.6 流式问答 ----------
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@RequestBody StreamRequest body) {
        Long userId = UserContext.requireId();
        long startAt = System.currentTimeMillis();

        if (body == null || !StringUtils.hasText(body.question())) {
            return Flux.just(errorEvent(400, "问题不能为空"));
        }
        String question = body.question().trim();
        if (question.length() > QUESTION_MAX_LEN) {
            return Flux.just(errorEvent(400, "问题不能超过 " + QUESTION_MAX_LEN + " 字"));
        }

        // 会话解析、上下文组装与用户消息落库：同步执行，此时仍在请求线程上
        String kind = "normal";   // B-28：会话类型（历史预算/画像注入/文件集检索的路由依据）
        ChatSession session;
        String finalQuestion;
        List<ChatTurn> history;
        try {
            session = chatSessionService.resolveOrCreate(userId, body.sessionId(), question);
            if (body.retryFromMessageId() != null) {
                // 重新生成：先删旧答案并取回原问题（含其消息 ID），
                // 再组装「原问题之前」的历史 —— 顺序反了会把旧答案和原问题都带进上下文
                ChatDTO.RetryContext ctx = chatSessionService.prepareRetry(
                        userId, session.getId(), body.retryFromMessageId());
                finalQuestion = ctx.question();
                history = chatSessionService.buildHistory(
                        session.getId(),
                        ChatSession.KIND_HEALTH.equals(kind) ? healthHistoryMaxChars : HISTORY_MAX_CHARS,
                        ctx.questionMessageId());
            } else {
                // 普通提问：本轮提问在组装历史之后才落库，因此不会混入上下文
                int budget = ChatSession.KIND_HEALTH.equals(kind) ? healthHistoryMaxChars : HISTORY_MAX_CHARS;
                history = chatSessionService.buildHistory(session.getId(), budget, null);
                ChatMessage userMsg = chatSessionService.saveUserMessage(userId, session.getId(), question);
                // B-28 二次完善：健康会话内抽取本轮陈述的指标事实（零 LLM 成本）
                if (ChatSession.KIND_HEALTH.equals(session.getKind())) {
                    healthContextService.extractAndSaveFacts(session.getId(), userId, userMsg.getId(), question);
                }
                finalQuestion = question;
            }
        } catch (RuntimeException e) {
            // 落库/鉴权失败要按 SSE 协议回 error，而不是抛成 500
            log.warn("流式问答前置处理失败, userId={}, sessionId={}", userId, body.sessionId(), e);
            return Flux.just(errorEvent(403, e.getMessage()));
        }

        // M16：专属对话画像注入——服务端按会话推导（08 §2.4），前端不可传；
        // 画像只影响生成、不参与检索，内容绝不写入 chat_message 任何列
        kind = session.getKind() == null ? ChatSession.KIND_NORMAL : session.getKind();
        String healthContext = ChatSession.KIND_HEALTH.equals(kind)
                ? healthContextService.buildHealthContext(userId, session.getId()) : null;

        // M15：kbScope 三态（07 §3.3）。includeInChat=false 时服务端强制忽略 personal
        // 成分（07 §5.4①，防前端绕过）；personal 且个人库为空 → 快速拒答不进检索
        String requestedScope = StringUtils.hasText(body.kbScope()) ? body.kbScope().trim() : "all";
        if (!List.of("all", "global", "personal").contains(requestedScope)) {
            requestedScope = "all";
        }
        if ("personal".equals(requestedScope) && !userKbService.includeInChat(userId)) {
            requestedScope = "global";
        }
        if ("personal".equals(requestedScope) && !userKbService.hasContent(userId)) {
            return Flux.just(errorEvent(2002, "你的资料库里还没有可用内容，先上传一些资料吧"));
        }

        ChatRequest request = new ChatRequest(
                history,
                finalQuestion,
                body.retryFromMessageId(),
                body.retrieveMode(),
                requestedScope,
                userId,
                healthContext,
                kind,
                session.getId()
        );

        StringBuilder fullText = new StringBuilder();
        long[] firstDeltaAt = {0L};
        AtomicBoolean errored = new AtomicBoolean(false);
        AtomicBoolean persisted = new AtomicBoolean(false);
        // 捕获 AI 层推送的引用与改写后检索词，供生成结束后落库。
        // 不这么做的话，消息列表里的 references 永远是空 —— 刷新页面引用就丢了。
        AtomicReference<List<ChunkRef>> refs = new AtomicReference<>(List.of());
        AtomicReference<String> queryRef = new AtomicReference<>(null);
        // 拒答/敏感拦截（error 2002/2004）：回答照常落库并标记 is_rejected（契约 02 §4.6）
        AtomicReference<ChatChunk.Error> rejectedRef = new AtomicReference<>(null);
        // 检索过程明细（B-23）：AI 层经 Trace 事件送达（不对外推送），随消息落库 trace_json
        AtomicReference<Map<String, Object>> traceRef = new AtomicReference<>(null);
        // B-27 双答案分账缓冲（单答案路径两缓冲恒空）
        StringBuilder answer1Text = new StringBuilder();
        StringBuilder answer2Text = new StringBuilder();
        // B-27/B-28：对照解答组 ID + 健康元数据 + 双答案计数
        AtomicReference<ChatChunk.HealthMeta> healthMetaRef = new AtomicReference<>(null);
        AtomicReference<String> conflictGroupRef = new AtomicReference<>(null);
        java.util.concurrent.atomic.AtomicInteger answerCountRef = new java.util.concurrent.atomic.AtomicInteger(0);
        // 实际生效的检索策略：客户端未指定时取服务端默认（当前 naive）
        String effectiveMode = (body.retrieveMode() != null && !body.retrieveMode().isBlank())
                ? body.retrieveMode() : defaultRetrieveMode;
        Long sessionId = session.getId();

        Flux<ServerSentEvent<Object>> events = chatService.chatStream(request)
                .mapNotNull(chunk -> toEvent(chunk, firstDeltaAt, fullText, errored,
                        answer1Text, answer2Text, refs, queryRef,
                        rejectedRef, traceRef, healthMetaRef, conflictGroupRef, answerCountRef))
                // 客户端断开 / 生成出错：保留已生成的部分内容（接口文档 §4.5 finishStatus=0）
                .doOnCancel(() -> persistInterrupted(userId, sessionId, fullText, firstDeltaAt,
                        startAt, persisted, refs.get(), queryRef.get(), effectiveMode, traceRef.get()))
                .doOnError(err -> persistInterrupted(userId, sessionId, fullText, firstDeltaAt,
                        startAt, persisted, refs.get(), queryRef.get(), effectiveMode, traceRef.get()));

        // done 由编排层组装：它依赖落库后的 messageId 与耗时统计，AI 层无从得知。
        //
        // 关于流结束：SSE 是单向流，本接口发完 done 即完成 Flux，Tomcat 随即关闭连接。
        // 实测客户端会读到 "terminated"（fetch）或 CURLE_PARTIAL_FILE(18)（curl），
        // 但 done 事件本身已完整送达 —— 这是 SSE 的固有行为，不是异常。
        // 前端应在收到 done 后主动关闭流（reader.cancel / controller.abort），
        // 并把该信号视为正常结束。曾尝试用延迟收尾解决，实测无效，故不做无用功。
        return events.concatWith(Flux.defer(() -> {
            ChatChunk.Error rejected = rejectedRef.get();
            if (rejected != null) {
                // 拒答样本落库：is_rejected=1，content 为引导话术，看板 rejectRate 由此产生真实数据
                persistRejected(userId, sessionId, rejected, startAt, firstDeltaAt[0],
                        persisted, effectiveMode, queryRef.get(), traceRef.get());
                return Flux.empty();   // 已发 error 事件，不再追加 done
            }
            if (errored.get()) {
                return Flux.empty();   // 已发 error 事件，不再追加 done
            }
            return Flux.just(doneEvent(userId, sessionId, startAt, firstDeltaAt[0],
                    fullText.toString(), persisted, refs.get(), queryRef.get(), effectiveMode,
                    traceRef.get(), request.healthContext() != null,
                    healthMetaRef.get() == null ? null : healthMetaRef.get().healthReminder(),
                    healthMetaRef.get() == null ? null : healthMetaRef.get().healthSuggestion(),
                    answerCountRef.get() > 1 ? answerCountRef.get() : null,
                    answer1Text, conflictGroupRef.get()));
        }));
    }

    private ServerSentEvent<Object> toEvent(ChatChunk chunk, long[] firstDeltaAt,
                                            StringBuilder fullText, AtomicBoolean errored,
                                            StringBuilder answer1Text, StringBuilder answer2Text,
                                            AtomicReference<List<ChunkRef>> refs,
                                            AtomicReference<String> queryRef,
                                            AtomicReference<ChatChunk.Error> rejectedRef,
                                            AtomicReference<Map<String, Object>> traceRef,
                                            AtomicReference<ChatChunk.HealthMeta> healthMetaRef,
                                            AtomicReference<String> conflictGroupRef,
                                            java.util.concurrent.atomic.AtomicInteger answerCountRef) {
        return switch (chunk) {
            case ChatChunk.Reference ref -> {
                refs.set(ref.chunks() == null ? List.of() : ref.chunks());
                if (ref.rewrittenQuery() != null && !ref.rewrittenQuery().isBlank()) {
                    queryRef.set(ref.rewrittenQuery());
                }
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("chunks", ref.chunks());
                data.put("rewrittenQuery", ref.rewrittenQuery() == null ? "" : ref.rewrittenQuery());
                if (ref.conflict() != null) {
                    data.put("conflict", ref.conflict());
                }
                yield ServerSentEvent.<Object>builder()
                        .event("reference")
                        .data(data)
                        .build();
            }

            // 阶段信号（B-13）：retrieve_start / retrieve_done / generate_start。
            // 前端未消费不影响既有渲染；retrieve_done 携带改写后检索词
            // 检索回放数据：只捕获不推送（return null 由 mapNotNull 过滤）
            case ChatChunk.Trace t -> {
                traceRef.set(t.payload());
                Object gid = t.payload().get("conflictGroupId");
                if (gid != null) {
                    conflictGroupRef.set(String.valueOf(gid));
                }
                yield null;
            }

            case ChatChunk.Stage stage -> {
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("stage", stage.name());
                if (stage.rewrittenQuery() != null) {
                    data.put("rewrittenQuery", stage.rewrittenQuery());
                }
                yield ServerSentEvent.<Object>builder()
                        .event("stage")
                        .data(data)
                        .build();
            }

            case ChatChunk.Delta delta -> {
                if (firstDeltaAt[0] == 0L) {
                    firstDeltaAt[0] = System.currentTimeMillis();
                }
                fullText.append(delta.content());
                // B-27 双答案分账：answerId=1 系统版 / 2 融合版（单答案 fullText 即融合全文）
                if ("1".equals(delta.answerId())) {
                    answer1Text.append(delta.content());
                } else if ("2".equals(delta.answerId())) {
                    answer2Text.append(delta.content());
                }
                Map<String, Object> ddata = new java.util.LinkedHashMap<>();
                ddata.put("content", delta.content());
                if (delta.answerId() != null) {
                    ddata.put("answerId", delta.answerId());
                }
                yield ServerSentEvent.<Object>builder()
                        .event("delta")
                        .data(ddata)
                        .build();
            }

            case ChatChunk.AnswerStart st -> {
                answerCountRef.incrementAndGet();
                yield ServerSentEvent.<Object>builder()
                        .event("answer_start")
                        .data(Map.of("answerId", st.answerId(), "source", st.source(), "label", st.label()))
                        .build();
            }

            case ChatChunk.AnswerEnd end -> {
                yield ServerSentEvent.<Object>builder()
                        .event("answer_end")
                        .data(Map.of("answerId", end.answerId()))
                        .build();
            }

            case ChatChunk.HealthMeta hm -> {
                healthMetaRef.set(hm);
                yield null;
            }

            case ChatChunk.Error err -> {
                errored.set(true);
                if (err.code() == 2002 || err.code() == 2004) {
                    rejectedRef.set(err);
                }
                yield ServerSentEvent.<Object>builder()
                        .event("error")
                        .data(Map.of("code", err.code(), "message", err.message()))
                        .build();
            }

            case ChatChunk.Done done -> ServerSentEvent.<Object>builder()
                    .event("done")
                    .data(done.info())
                    .build();
        };
    }

    private ServerSentEvent<Object> doneEvent(Long userId, Long sessionId, long startAt,
                                              long firstDeltaAt, String content, AtomicBoolean persisted,
                                              List<ChunkRef> refs, String queryText, String retrieveMode,
                                              Map<String, Object> tracePayload, boolean healthUsed,
                                              Map<String, Object> healthReminder,
                                              String healthSuggestion, Integer answerCount,
                                              StringBuilder answer1Text, String conflictGroup) {
        long now = System.currentTimeMillis();
        int latencyMs = (int) (now - startAt);
        int firstTokenMs = firstDeltaAt == 0L ? latencyMs : (int) (firstDeltaAt - startAt);

        ChatMessage saved;
        try {
            persisted.set(true);
            // B-27 对照解答落库：双答案 = 两条消息（global/fused 各一条，同组共享 groupId）
            if (answerCount != null && answerCount == 2 && answer1Text.length() > 0) {
                String gid = conflictGroup != null ? conflictGroup : java.util.UUID.randomUUID().toString();
                chatSessionService.saveAssistantMessage(userId, sessionId, answer1Text.toString(),
                        latencyMs, firstTokenMs, DoneInfo.FINISH_COMPLETED, retrieveMode, queryText,
                        false, refs, "global", gid);
                saved = chatSessionService.saveAssistantMessage(userId, sessionId, content,
                        latencyMs, firstTokenMs, DoneInfo.FINISH_COMPLETED, retrieveMode, queryText,
                        false, refs, "fused", gid);
            } else {
                saved = chatSessionService.saveAssistantMessage(
                        userId, sessionId, content, latencyMs, firstTokenMs,
                        DoneInfo.FINISH_COMPLETED, retrieveMode, queryText, false, refs, null, null);
            }
            chatSessionService.saveTrace(saved.getId(), tracePayload);
            chatSessionService.touch(sessionId);
        } catch (RuntimeException e) {
            // 落库失败不应让已经生成完的回答白费：降级为不落库，仍回传完整内容
            log.error("助手消息落库失败, userId={}, sessionId={}", userId, sessionId, e);
            saved = null;
        }

        DoneInfo info = new DoneInfo(
                saved == null ? null : saved.getId(),
                sessionId,
                latencyMs,
                firstTokenMs,
                DoneInfo.FINISH_COMPLETED,
                content,
                healthUsed,
                healthReminder,
                healthSuggestion,
                answerCount
        );
        return ServerSentEvent.<Object>builder().event("done").data(info).build();
    }

    private void persistInterrupted(Long userId, Long sessionId, StringBuilder fullText,
                                    long[] firstDeltaAt, long startAt, AtomicBoolean persisted,
                                    List<ChunkRef> refs, String queryText, String retrieveMode,
                                    Map<String, Object> tracePayload) {
        if (!persisted.compareAndSet(false, true)) {
            return;
        }
        String content = fullText.toString();
        if (content.isEmpty()) {
            return;   // 一个字都没生成，落库没有意义
        }
        long now = System.currentTimeMillis();
        int latencyMs = (int) (now - startAt);
        int firstTokenMs = firstDeltaAt[0] == 0L ? latencyMs : (int) (firstDeltaAt[0] - startAt);
        try {
            ChatMessage interrupted = chatSessionService.saveAssistantMessage(
                    userId, sessionId, content, latencyMs, firstTokenMs,
                    DoneInfo.FINISH_INCOMPLETE, retrieveMode, queryText, false, refs, null, null);
            chatSessionService.saveTrace(interrupted.getId(), tracePayload);
            chatSessionService.touch(sessionId);
        } catch (RuntimeException e) {
            log.error("中断消息落库失败, userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    /** 拒答/敏感拦截样本落库（finishStatus=1，is_rejected=1） */
    private void persistRejected(Long userId, Long sessionId, ChatChunk.Error rejected,
                                 long startAt, long firstDeltaAt, AtomicBoolean persisted,
                                 String retrieveMode, String queryText, Map<String, Object> tracePayload) {
        if (!persisted.compareAndSet(false, true)) {
            return;
        }
        long now = System.currentTimeMillis();
        int latencyMs = (int) (now - startAt);
        int firstTokenMs = firstDeltaAt == 0L ? latencyMs : (int) (firstDeltaAt - startAt);
        try {
            ChatMessage rejectedMsg = chatSessionService.saveAssistantMessage(userId, sessionId,
                    rejected.message(), latencyMs, firstTokenMs, DoneInfo.FINISH_COMPLETED,
                    retrieveMode, queryText, true, List.of(), null, null);
            chatSessionService.saveTrace(rejectedMsg.getId(), tracePayload);
            chatSessionService.touch(sessionId);
        } catch (RuntimeException e) {
            log.error("拒答消息落库失败, userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    private static ServerSentEvent<Object> errorEvent(int code, String message) {
        return ServerSentEvent.<Object>builder()
                .event("error")
                .data(Map.of("code", code, "message", message))
                .build();
    }

    /**
     * 流式问答请求体。
     *
     * <p><b>没有 history 字段是刻意的</b>：上下文由服务端从 {@code chat_message} 组装
     * （见 {@link ChatSessionService#buildHistory}）。接受客户端提交历史等于开放一个
     * prompt injection 入口 —— 客户端可伪造任意对话内容甚至夹带指令塞进 Prompt。
     * 旧字段已移除；客户端仍在传时会被忽略（Jackson 默认不因未知字段报错）。
     */
    public record StreamRequest(
            Long sessionId,
            String question,
            Long retryFromMessageId,
            String retrieveMode,
            String kbScope
    ) {
    }

    /** §4.7 反馈入参 */
    public record FeedbackRequest(String type, String comment) {
    }
}
