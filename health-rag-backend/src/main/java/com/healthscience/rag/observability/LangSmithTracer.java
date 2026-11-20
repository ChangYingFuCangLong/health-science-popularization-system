package com.healthscience.rag.observability;

import com.healthscience.rag.ai.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * LangSmith 链路追踪上报（HTTP 直连 Run Trees API）。
 *
 * <p><b>为什么不用官方 Java SDK</b>：{@code com.langchain.smith:langsmith-java}
 * 仍是 0.1.0-beta（45 个传递依赖、3 个安全公告含 1 个 HIGH），
 * 而我们要的只是上报几个 run —— 直连 API 约 100 行即可，可控且无额外风险。
 *
 * <p><b>铁律：追踪绝不能影响主链路</b>。上报全程异步 + 短超时 + 吞掉所有异常；
 * 关掉开关后本组件完全不装配，问答行为不变。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "health.observability.langsmith-enabled", havingValue = "true")
public class LangSmithTracer {

    private static final DateTimeFormatter DOTTED =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final int TIMEOUT_SECONDS = 3;
    private static final int INPUT_CLIP = 800;
    private static final int OUTPUT_CLIP = 2000;

    private final RestClient client;
    private final String project;

    public LangSmithTracer(
            @Value("${LANGSMITH_API_KEY:${langsmith.api-key:}}") String apiKey,
            @Value("${langsmith.endpoint:https://api.smith.langchain.com}") String endpoint,
            @Value("${langsmith.project:health-rag-backend}") String project,
            @Value("${langsmith.proxy-host:}") String proxyHost,
            @Value("${langsmith.proxy-port:0}") int proxyPort) {
        this.project = project;

        // 单独给追踪配 HTTP 客户端：LangSmith 在境外，而 Ollama / MySQL 在本机，
        // 若用 JVM 全局代理会把本地调用也绕进去。这里只影响上报请求。
        java.net.http.HttpClient.Builder httpBuilder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS));
        java.net.InetSocketAddress proxy = resolveProxy(proxyHost, proxyPort);
        if (proxy != null) {
            httpBuilder.proxy(java.net.ProxySelector.of(proxy));
        }
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpBuilder.build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS));

        this.client = RestClient.builder()
                .baseUrl(endpoint)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey == null ? "" : apiKey)
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[LangSmith] 未配置 LANGSMITH_API_KEY，追踪将上报失败（不影响主链路）");
        } else {
            log.info("[LangSmith] 追踪已启用，project={}，proxy={}", project, proxy);
        }
    }

    /**
     * 解析代理：优先用显式配置，否则回退到环境变量 {@code https_proxy}。
     * 本机实测：直连约 6.5s（会触发超时），走代理约 1.5s。
     */
    private static java.net.InetSocketAddress resolveProxy(String host, int port) {
        if (host != null && !host.isBlank() && port > 0) {
            return new java.net.InetSocketAddress(host.trim(), port);
        }
        String env = System.getenv("https_proxy");
        if (env == null || env.isBlank()) {
            env = System.getenv("HTTPS_PROXY");
        }
        if (env == null || env.isBlank()) {
            return null;
        }
        try {
            java.net.URI u = java.net.URI.create(env.contains("://") ? env : "http://" + env);
            return new java.net.InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80);
        } catch (Exception e) {
            return null;
        }
    }

    /** 一次问答的追踪上下文：根 run + 两个子 run */
    public static final class Trace {
        private final String rootId = UUID.randomUUID().toString();
        private final String retrievalId = UUID.randomUUID().toString();
        private final String generationId = UUID.randomUUID().toString();
        private final String rootDot;
        private final String retrievalDot;
        private final String generationDot;
        private final Instant start = Instant.now();
        private Instant retrievalEnd;
        private long retrievalMs;
        private int hitCount;

        private Trace() {
            this.rootDot = DOTTED.format(start) + rootId;
            this.retrievalDot = rootDot + "." + DOTTED.format(start) + retrievalId;
            this.generationDot = rootDot + "." + DOTTED.format(start) + generationId;
        }

        public void retrievalDone(List<RetrievedChunk> chunks, long ms) {
            this.retrievalMs = ms;
            this.retrievalEnd = Instant.now();
            this.hitCount = chunks == null ? 0 : chunks.size();
        }
    }

    public Trace newTrace() {
        return new Trace();
    }

    /**
     * 上报一次问答的完整链路。异步执行，失败仅记日志。
     *
     * @param question  用户提问
     * @param mode      实际检索策略
     * @param chunks    命中的分块
     * @param answer    生成结果
     * @param extraMs   firstTokenMs / latencyMs 等指标
     */
    public void report(Trace trace, String question, String mode,
                       List<RetrievedChunk> chunks, String answer, Map<String, Object> extraMs) {
        Trace t = trace;
        Instant end = Instant.now();
        List<Map<String, Object>> runs = new ArrayList<>(3);

        // 根：整条 RAG 链路
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", t.rootId);
        root.put("name", "rag-qa");
        root.put("run_type", "chain");
        root.put("session_name", project);
        root.put("inputs", Map.of("question", clip(question, INPUT_CLIP)));
        root.put("outputs", Map.of("answer", clip(answer, OUTPUT_CLIP)));
        root.put("start_time", iso(t.start));
        root.put("end_time", iso(end));
        root.put("trace_id", t.rootId);
        root.put("dotted_order", t.rootDot);
        putExtra(root, extraMs, mode);
        runs.add(root);

        // 子：检索
        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("id", t.retrievalId);
        retrieval.put("name", "retrieval");
        retrieval.put("run_type", "retriever");
        retrieval.put("parent_run_id", t.rootId);
        retrieval.put("session_name", project);
        retrieval.put("inputs", Map.of("query", clip(question, INPUT_CLIP), "mode", mode));
        retrieval.put("outputs", Map.of("documents", docSummaries(chunks)));
        retrieval.put("start_time", iso(t.start));
        retrieval.put("end_time", iso(t.retrievalEnd == null ? end : t.retrievalEnd));
        retrieval.put("trace_id", t.rootId);
        retrieval.put("dotted_order", t.retrievalDot);
        retrieval.put("extra", Map.of("hitCount", t.hitCount, "elapsedMs", t.retrievalMs));
        runs.add(retrieval);

        // 子：生成
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("id", t.generationId);
        gen.put("name", "generation");
        gen.put("run_type", "llm");
        gen.put("parent_run_id", t.rootId);
        gen.put("session_name", project);
        gen.put("inputs", Map.of("promptWithContext", clip(question, INPUT_CLIP)));
        gen.put("outputs", Map.of("content", clip(answer, OUTPUT_CLIP)));
        gen.put("start_time", iso(t.retrievalEnd == null ? t.start : t.retrievalEnd));
        gen.put("end_time", iso(end));
        gen.put("trace_id", t.rootId);
        gen.put("dotted_order", t.generationDot);
        putExtra(gen, extraMs, mode);
        runs.add(gen);

        CompletableFuture.runAsync(() -> post(runs));
    }

    private void post(List<Map<String, Object>> runs) {
        try {
            client.post()
                    .uri("/api/v1/runs/batch")
                    .body(Map.of("post", runs, "patch", List.of()))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[LangSmith] 已上报 {} 个 run", runs.size());
        } catch (Exception e) {
            // 吞掉：追踪失败不能影响问答
            log.warn("[LangSmith] 上报失败（已忽略）: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void putExtra(Map<String, Object> run, Map<String, Object> metrics, String mode) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (metrics != null) {
            extra.putAll(metrics);
        }
        extra.put("retrieveMode", mode);
        run.put("extra", extra);
    }

    private static List<Map<String, Object>> docSummaries(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> docs = new ArrayList<>(chunks.size());
        for (RetrievedChunk c : chunks) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("chunkId", c.chunkId());
            d.put("title", c.meta("title"));
            d.put("source", c.meta("source"));
            d.put("score", c.score());
            d.put("content", clip(c.content(), 300));
            docs.add(d);
        }
        return docs;
    }

    private static String iso(Instant t) {
        return DateTimeFormatter.ISO_INSTANT.format(t);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
