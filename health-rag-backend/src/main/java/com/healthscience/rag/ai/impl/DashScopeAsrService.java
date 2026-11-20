package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.AsrService;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 语音识别实现（DashScope qwen3-asr-flash 直连，任务卡 B-18）。
 *
 * <p><b>Spike 结论（2026-09-19 实测钉定，写进 ADR）</b>：走
 * {@code POST /api/v1/services/aigc/multimodal-generation/generation}，
 * messages content 项<b>必须带 type 字段</b>：
 * {@code [{"type":"audio","audio":"data:audio/wav;base64,..."},{"type":"text","text":"..."}]}；
 * 不带 type 报 "does not support this input"。OpenAI 兼容端点不支持该模型。
 * 音频时长从响应 {@code usage.seconds} 取得。
 *
 * <p><b>降级纪律</b>：识别失败抛 2005（契约），绝不阻塞文本问答主链路；
 * Paraformer 录音文件识别为兜底通道（当前未启用，异步任务式，体验差一档）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.ai.provider", havingValue = "dashscope")
public class DashScopeAsrService implements AsrService {

    private static final String ENDPOINT = "/api/v1/services/aigc/multimodal-generation/generation";
    private static final String MODEL = "qwen3-asr-flash";

    private final RestClient client;

    public DashScopeAsrService(
            @Value("${health.ai.dashscope-api-key:}") String apiKey,
            @Value("${health.ai.asr-url:https://dashscope.aliyuncs.com}") String baseUrl) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));   // 60s 音频的识别耗时上限
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        log.info("[Asr] 已装配：{}{}", baseUrl, ENDPOINT);
    }

    @Override
    public AsrResult transcribe(byte[] audio, String contentType) {
        String dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(audio);
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "input", Map.of("messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "audio", "audio", dataUrl),
                                Map.of("type", "text", "text", ""))))));

        Map<String, Object> resp;
        try {
            resp = client.post()
                    .uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.warn("[Asr] 识别请求失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ASR_FAILED, "语音识别失败，请重试");
        }
        if (resp == null) {
            throw new BizException(ErrorCode.ASR_FAILED, "语音识别失败，请重试");
        }

        String text = extractText(resp);
        int seconds = extractAudioSeconds(resp);
        log.info("[Asr] 识别完成：{} 字，音频 {} 秒", text.length(), seconds);
        return new AsrResult(text, seconds);
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> resp) {
        try {
            Map<String, Object> output = (Map<String, Object>) resp.get("output");
            List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            Object content = message.get("content");
            // content 可能是字符串，也可能是分片段数组（[{type:text,text:...}]）
            if (content instanceof String s) {
                return s.trim();
            }
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<Object>) content) {
                Map<String, Object> part = (Map<String, Object>) item;
                if (part.get("text") != null) {
                    sb.append(part.get("text"));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static int extractAudioSeconds(Map<String, Object> resp) {
        try {
            Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
            Object seconds = usage.get("seconds");
            return seconds == null ? 0 : Integer.parseInt(String.valueOf(seconds));
        } catch (Exception e) {
            return 0;
        }
    }
}
