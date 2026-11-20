package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.ChatService;
import com.healthscience.rag.ai.QueryRewriteService;
import com.healthscience.rag.ai.RetrievalService;
import com.healthscience.rag.ai.SafetyPolicy;
import com.healthscience.rag.ai.model.ChatChunk;
import com.healthscience.rag.ai.model.ChatRequest;
import com.healthscience.rag.ai.model.ChatTurn;
import com.healthscience.rag.ai.model.ChunkRef;
import com.healthscience.rag.ai.model.RetrievalResult;
import com.healthscience.rag.ai.model.RetrievedChunk;
import com.healthscience.rag.ai.model.RetrieveMode;
import com.healthscience.rag.observability.LangSmithTracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 对话实现（M6）：检索 → 注入上下文 → 生成，并在生成前推送真实引用。
 *
 * <p><b>与 {@link DashScopeChatService} 的关系</b>：二者是同一接口的两个实现，
 * 由 {@code health.ai.rag-enabled} 二选一装配 —— 这正是架构约束 C1 的收益：
 * 从纯对话演进到 RAG，Controller 与前端<b>零改动</b>。
 *
 * <p><b>检索为空时为什么不拒答</b>：接口文档 §4.6 定义了错误码 2002（拒答），
 * 但知识库不可能覆盖所有健康问题，无结果就拒答会让大量正常提问失败。
 * 这里降级为「无引用的纯对话」—— 前端逻辑与纯对话阶段一致（reference 为空数组），
 * 用户仍能得到回答，论文讨论检索覆盖率时再引入拒答阈值。
 *
 * <p><b>首字延迟</b>：检索发生在生成之前，耗时会计入 {@code firstTokenMs}。
 * 这是 RAG 与纯对话的核心差异，也是 NFR 需要在 RAG 场景下重新测的原因。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.ai.rag-enabled", havingValue = "true")
public class RagChatService implements ChatService {

    private static final int DIGEST_LEN = 100;

    /** SSE stage 事件名（任务卡 B-13，契约 02 §4.6） */
    public static final String STAGE_RETRIEVE_START = "retrieve_start";
    public static final String STAGE_RETRIEVE_DONE = "retrieve_done";
    public static final String STAGE_GENERATE_START = "generate_start";
    private static final double TEMPERATURE = 0.3;

    private static final String SYSTEM_PROMPT = """
            你是一个面向大学生群体的健康科普助手。回答要求：
            1. 优先依据提供的参考资料作答；资料未覆盖时可用医学常识补充，但须说明；
            2. 引用资料时在句末标注对应编号，如 [1]；
            3. 用通俗易懂的中文，避免不必要的专业术语；
            4. 涉及诊断、用药或紧急情况时，明确建议就医并咨询专业医生；
            5. 回答控制在 500 字以内，先给结论再展开；
            6. 标注【用户本人上传的资料】的参考资料，是用户自己的记录：当问题问"我的……"时，
               必须直接引用该记录作答，不要回答"无法知道"；若用户陈述的数值与记录不一致，
               以记录为准并明确指出差异（例如"你提到 160/100，但你上传的记录是 118/76"）。
            """;

    private final OpenAiChatModel chatModel;
    private final RetrievalService retrievalService;
    /**
     * 用 ObjectProvider 而非直接注入：追踪组件由 {@code health.observability.langsmith-enabled}
     * 控制装配，关闭时 bean 不存在，直接注入会导致本服务启动失败。
     */
    /** 安全策略：闸门①危机意图拦截 + 闸门②置信度拒答（B-16） */
    private final SafetyPolicy safetyPolicy;
    private final org.springframework.beans.factory.ObjectProvider<LangSmithTracer> tracerProvider;
    /**
     * 改写组件由 provider 非 dashscope 时不装配（ObjectProvider 探测），关闭时本服务行为不变。
     */
    private final org.springframework.beans.factory.ObjectProvider<QueryRewriteService> queryRewriteProvider;
    private final int topK;
    private final boolean thinkingEnabled;
    private final String defaultMode;
    /** 思考参数协议适配：百炼 {@code enable_thinking} / DeepSeek {@code thinking}（见 ReasoningParams） */
    private final ReasoningParams reasoningParams;

    public RagChatService(OpenAiChatModel chatModel,
                          RetrievalService retrievalService,
                          SafetyPolicy safetyPolicy,
                          org.springframework.beans.factory.ObjectProvider<LangSmithTracer> tracerProvider,
                          org.springframework.beans.factory.ObjectProvider<QueryRewriteService> queryRewriteProvider,
                          @Value("${health.rag.top-k:5}") int topK,
                          @Value("${health.ai.thinking-enabled:false}") boolean thinkingEnabled,
                          @Value("${health.rag.default-mode:hybrid}") String defaultMode,
                          ReasoningParams reasoningParams) {
        this.chatModel = chatModel;
        this.retrievalService = retrievalService;
        this.safetyPolicy = safetyPolicy;
        this.tracerProvider = tracerProvider;
        this.queryRewriteProvider = queryRewriteProvider;
        this.topK = topK;
        this.thinkingEnabled = thinkingEnabled;
        this.defaultMode = defaultMode;
        this.reasoningParams = reasoningParams;
        log.info("[RagChatService] 已启用，topK={}, defaultMode={}, thinkingEnabled={}",
                topK, defaultMode, thinkingEnabled);
    }

    @Override
    public String chat(ChatRequest request) {
        // 同步路径同样过安全闸门：危机话术 / 拒答文案直接作为回答返回
        if (safetyPolicy.isSensitive(request.question())) {
            return SafetyPolicy.CRISIS_MESSAGE;
        }
        RetrievalResult result = retrieve(request);
        if (safetyPolicy.belowConfidence(result.vectorTopScore())) {
            return SafetyPolicy.REJECT_MESSAGE;
        }
        return chatModel.call(buildPrompt(request, result.chunks())).getResult().getOutput().getText();
    }

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        LangSmithTracer tracer = tracerProvider.getIfAvailable();
        LangSmithTracer.Trace trace = tracer == null ? null : tracer.newTrace();
        long startedAt = System.currentTimeMillis();

        // 闸门①（B-16）：危机/急救意图不检索不生成，NONE 模式同样生效
        if (safetyPolicy.isSensitive(request.question())) {
            return wrapTrace(Flux.just(
                    new ChatChunk.Trace(new java.util.LinkedHashMap<>(Map.of(
                            "decision", "BLOCKED_BY_SAFETY",
                            "originalQuestion", request.question()))),
                    new ChatChunk.Error(2004, SafetyPolicy.CRISIS_MESSAGE)),
                    tracer, trace, request, resolveMode(request), List.of(), startedAt);
        }

        String mode = resolveMode(request);
        if (RetrieveMode.NONE.equals(mode)) {
            // 消融实验的对照组：不检索，行为等同纯对话（无检索阶段信号）
            return wrapTrace(Flux.concat(Flux.just(ChatChunk.Reference.empty()),
                    streamDeltas(request, List.of())), tracer, trace, request, mode, List.of(), startedAt);
        }
        boolean fileSession = "file".equals(request.sessionKind()) && request.sessionId() != null;
        if (!fileSession && !retrievalService.isReady()) {
            // 索引不就绪必须显式拦截：否则会给出看似正常实则错乱的答案。
            // file 会话不依赖全局库就绪（文件集独立，12 号裁决）
            return wrapTrace(Flux.just(new ChatChunk.Error(2003, "向量索引未就绪，请先在管理端重建索引")),
                    tracer, trace, request, mode, List.of(), startedAt);
        }

        // 查询改写（B-12）：失败/关闭时内部降级为原问题，绝不让主链路失败
        String rewritten = rewriteQuery(request);
        boolean rewriteAvailable = queryRewriteProvider.getIfAvailable() != null;
        RetrievalResult result;
        try {
            // B-31：file 会话检索硬限定在该会话的文件集（stream 不加参数，按 kind 自动路由）
            result = fileSession
                    ? retrievalService.retrieveInSession(rewritten, request.sessionId(), topK)
                    : retrievalService.retrieve(rewritten, mode, topK,
                            request.kbScope(), request.ownerUserId());
        } catch (Exception e) {
            // 检索依赖（本地 embedding 服务）挂掉时降级为「无引用回答」，而不是让整条问答 500。
            // 检索只是增强手段，模型本身仍能回答 —— 实测 Ollama 进程被回收时
            // 这里会抛 ConnectException，未处理会直接冒泡成 HTTP 500。
            log.error("[RagChatService] 检索失败，降级为无引用回答", e);
            result = RetrievalResult.empty();
        }
        List<RetrievedChunk> chunks = result.chunks();
        if (trace != null) {
            trace.retrievalDone(chunks, System.currentTimeMillis() - startedAt);
        }

        // 检索过程回放数据（B-23）：检索层明细 + 编排层决策字段，随消息落库 trace_json
        Map<String, Object> tracePayload = new java.util.LinkedHashMap<>(result.trace() == null
                ? Map.of() : result.trace());
        tracePayload.put("originalQuestion", request.question());
        tracePayload.put("rewrittenQuery", rewritten);
        tracePayload.put("rewriteEnabled", rewriteAvailable);
        tracePayload.put("rewriteApplied", !rewritten.equals(request.question()));
        tracePayload.put("vectorTopScore", result.vectorTopScore());
        tracePayload.put("rejectThreshold", safetyPolicy.rejectThreshold());

        // 闸门②（B-16）：向量路 top1 余弦低于阈值 → 拒答（不用 RRF 分数，见 SafetyPolicy）
        if (safetyPolicy.belowConfidence(result.vectorTopScore())) {
            log.info("[RagChatService] 置信度拒答: query={}, topScore={}", rewritten, result.vectorTopScore());
            tracePayload.put("decision", "REJECTED_LOW_CONFIDENCE");
            return wrapTrace(Flux.just(new ChatChunk.Trace(tracePayload),
                            new ChatChunk.Error(2002, SafetyPolicy.REJECT_MESSAGE)),
                    tracer, trace, request, mode, List.of(), startedAt);
        }
        if (chunks.isEmpty()) {
            log.info("[RagChatService] 未检索到内容，降级为无引用回答: {}", rewritten);
            tracePayload.put("decision", "ANSWER_NO_HITS");
        } else {
            tracePayload.put("decision", "ANSWER");
        }

        // 阶段信号（B-13）：retrieve_start → retrieve_done(带改写词) → reference → generate_start → deltas。
        // 前端未消费时行为不变（纯增益）；事件在订阅时按序送达，改写/检索仍在请求线程同步完成
        List<ChatChunk> head = new ArrayList<>(5);
        head.add(new ChatChunk.Trace(tracePayload));
        head.add(new ChatChunk.Stage(STAGE_RETRIEVE_START));
        head.add(new ChatChunk.Stage(STAGE_RETRIEVE_DONE, rewritten));

        // B-27 对照解答（仅健康会话 + 冲突判定通过，07 §2.2/2.3）：
        // 系统答案（纯基础库）→ 融合答案（含个人资料），串行两次生成（延迟约 2×，如实写文档）
        boolean dual = result.conflict() != null
                && com.healthscience.rag.entity.ChatSession.KIND_HEALTH.equals(request.sessionKind());
        if (dual) {
            String groupId = java.util.UUID.randomUUID().toString();
            tracePayload.put("conflictGroupId", groupId);
            tracePayload.put("decision", "DUAL_ANSWER");
            List<RetrievedChunk> globalOnly = chunks.stream()
                    .filter(c -> "global".equals(c.meta("scope")))
                    .toList();
            head.add(new ChatChunk.Reference(toRefs(chunks), rewritten, result.conflict()));
            head.add(new ChatChunk.Stage(STAGE_GENERATE_START));
            head.add(new ChatChunk.AnswerStart(1, "global", "系统答案"));
            Flux<ChatChunk> answer1 = streamDeltasTagged(request, globalOnly, "1");
            Flux<ChatChunk> mid = Flux.just(new ChatChunk.AnswerEnd(1),
                    new ChatChunk.AnswerStart(2, "fused", "融合答案（含你的资料）"));
            Flux<ChatChunk> answer2 = streamDeltasTagged(request, chunks, "2");
            return wrapTrace(Flux.concat(Flux.fromIterable(head), answer1, mid, answer2,
                            Flux.just(new ChatChunk.AnswerEnd(2))),
                    tracer, trace, request, mode, chunks, startedAt);
        }

        head.add(new ChatChunk.Reference(toRefs(chunks), rewritten, null));
        head.add(new ChatChunk.Stage(STAGE_GENERATE_START));
        return wrapTrace(Flux.concat(Flux.fromIterable(head), streamDeltas(request, chunks)),
                tracer, trace, request, mode, chunks, startedAt);
    }

    /** 带答案标记的流式生成（B-27 对照解答：answerId 标记归属系统/融合答案） */
    private Flux<ChatChunk> streamDeltasTagged(ChatRequest request, List<RetrievedChunk> chunks, String answerId) {
        return streamDeltas(request, chunks)
                .filter(c -> c instanceof ChatChunk.Delta)
                .map(c -> new ChatChunk.Delta(((ChatChunk.Delta) c).content(), answerId));
    }

    /** 改写服务未装配（非 dashscope provider）时原样返回当前问题 */
    private String rewriteQuery(ChatRequest request) {
        QueryRewriteService rewriter = queryRewriteProvider.getIfAvailable();
        return rewriter == null ? request.question()
                : rewriter.rewrite(request.history(), request.question());
    }

    /**
     * 在流结束时上报链路追踪。用 {@code doFinally} 而非 {@code doOnComplete}：
     * 客户端中途断开也要留下 trace，否则排查"为什么用户没等到回答"时没有依据。
     */
    private Flux<ChatChunk> wrapTrace(Flux<ChatChunk> source, LangSmithTracer tracer,
                                      LangSmithTracer.Trace trace, ChatRequest request,
                                      String mode, List<RetrievedChunk> chunks, long startedAt) {
        if (tracer == null || trace == null) {
            return source;
        }
        StringBuilder answer = new StringBuilder();
        return source
                .doOnNext(c -> {
                    if (c instanceof ChatChunk.Delta d) {
                        answer.append(d.content());
                    }
                })
                .doFinally(signal -> tracer.report(trace, request.question(), mode, chunks,
                        answer.toString(),
                        Map.of("totalMs", System.currentTimeMillis() - startedAt,
                                "signal", String.valueOf(signal))));
    }

    private Flux<ChatChunk> streamDeltas(ChatRequest request, List<RetrievedChunk> chunks) {
        return chatModel.stream(buildPrompt(request, chunks))
                .mapNotNull(resp -> {
                    String text = resp.getResult() == null
                            ? null : resp.getResult().getOutput().getText();
                    if (text == null || text.isEmpty()) {
                        return null;
                    }
                    ChatChunk chunk = new ChatChunk.Delta(text);
                    return chunk;
                });
    }

    private RetrievalResult retrieve(ChatRequest request) {
        if (RetrieveMode.NONE.equals(resolveMode(request)) || !retrievalService.isReady()) {
            return RetrievalResult.empty();
        }
        return retrievalService.retrieve(rewriteQuery(request), resolveMode(request), topK,
                request.kbScope(), request.ownerUserId());
    }

    private String resolveMode(ChatRequest request) {
        if (request.retrieveMode() != null && !request.retrieveMode().isBlank()) {
            return request.retrieveMode();
        }
        return defaultMode;
    }

    private Prompt buildPrompt(ChatRequest request, List<RetrievedChunk> chunks) {
        List<Message> messages = new ArrayList<>();

        StringBuilder system = new StringBuilder(SYSTEM_PROMPT);
        if (!chunks.isEmpty()) {
            system.append("\n\n参考资料：\n");
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk c = chunks.get(i);
                String title = c.meta("title");
                String source = c.meta("source");
                system.append('[').append(i + 1).append("] ");
                // D5 配套：个人资料来自用户自己上传，必须让模型知道"这是本人的记录"，
                // 否则会把随访记录当成普通科普文，答"我无法知道你的数值"（测试报告 §21.3）
                if ("personal".equals(c.meta("scope"))) {
                    system.append("【用户本人上传的资料】");
                }
                if (!title.isEmpty()) {
                    system.append("《").append(title).append("》");
                }
                if (!source.isEmpty()) {
                    system.append("（来源：").append(source).append("）");
                }
                system.append('\n').append(clip(c.content())).append("\n\n");
            }
        }
        // M16：健康画像注入（08 §2.1——只影响生成，不参与检索；内容由服务端按会话推导，
        // 已截断 ≤600 字；绝不写入 chat_message 任何列）
        if (request.healthContext() != null && !request.healthContext().isBlank()) {
            system.append(request.healthContext()).append("\n\n");
        }
        messages.add(new SystemMessage(system.toString()));

        if (request.history() != null) {
            for (ChatTurn turn : request.history()) {
                if (ChatTurn.ROLE_USER.equals(turn.role())) {
                    messages.add(new UserMessage(turn.content()));
                } else if (ChatTurn.ROLE_ASSISTANT.equals(turn.role())) {
                    messages.add(new AssistantMessage(turn.content()));
                }
            }
        }
        messages.add(new UserMessage(request.question()));

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(TEMPERATURE)
                .extraBody(reasoningParams.extraBody(thinkingEnabled))
                .build();
        return new Prompt(messages, options);
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        // 单块最长 500 字，这里不再截断，避免切掉关键句
        return text.trim();
    }

    private List<ChunkRef> toRefs(List<RetrievedChunk> chunks) {
        List<ChunkRef> refs = new ArrayList<>(chunks.size());
        for (RetrievedChunk c : chunks) {
            String content = c.content() == null ? "" : c.content();
            String digest = content.length() <= DIGEST_LEN
                    ? content : content.substring(0, DIGEST_LEN);
            refs.add(new ChunkRef(
                    c.chunkId(),
                    parseLong(c.meta("documentId")),
                    c.meta("title"),
                    c.meta("section"),        // B-17：结构化分块（M12 上传）产出章节；V1 历史语料为空串
                    digest,
                    c.score(),
                    c.meta("scope").isBlank() ? "global" : c.meta("scope")));
        }
        return refs;
    }

    private static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
