package com.healthscience.rag.ai.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 思考模式（reasoning）参数的<b>协议适配层</b>。
 *
 * <h3>为什么需要它</h3>
 * <p>"关掉思考链"这件事，两家 OpenAI 兼容服务用的是<b>不同字段</b>：
 * <ul>
 *   <li><b>阿里百炼</b>：{@code {"enable_thinking": false}}（百炼私有扩展）</li>
 *   <li><b>DeepSeek</b>：{@code {"thinking": {"type": "disabled"}}}</li>
 * </ul>
 * 两者都必须放在请求体顶层，由 Spring AI 的
 * {@link org.springframework.ai.openai.OpenAiChatOptions.Builder#extraBody(Map)} 透传。
 *
 * <p>此前这行 {@code Map.of("enable_thinking", ...)} 在 6 个 Service 里各写了一份
 * （实测 grep：RagChatService / DashScopeChatService / DashScopeSuggestionService /
 * DashScopeQueryRewriteService / DashScopeEvalScoringService / ReportLlmExtractor）。
 * 换供应商时改漏一处，那一路就会带着未知字段请求对方 API，行为不可预期
 * （本项目已因"推理帧污染首字延迟"踩过坑，见 {@link DashScopeChatService} 类注释）。
 * 故收敛到本类：<b>切换供应商只改配置，不需要再动 6 个业务类</b>。
 *
 * <h3>判定依据</h3>
 * <p>按 {@code spring.ai.openai.base-url} 是否包含 {@code deepseek} 自动判定，
 * 避免再引入一个需要人工同步的开关项（少一个能忘改的配置，就少一种线上事故）。
 *
 * <h3>实测依据（2026-11-19，deepseek-flash）</h3>
 * <pre>
 *   不传 thinking：content="OK", reasoning_content="The user wants exactly one word: OK."
 *                  usage = {prompt 37, completion 11}, 其中 reasoning_tokens = 9
 *   传 thinking:{type:disabled}：content="OK", reasoning_content=""
 *                  usage = {prompt 11, completion 1},  reasoning_tokens 无
 * </pre>
 * 同一句"回复一个词 OK"，开思考的 token 消耗是关思考的 4 倍，
 * 且流式响应会先推大量 {@code reasoning_content} 帧（{@code content} 恒为空），
 * 首字延迟指标随之失真 —— 这正是本项目默认关闭思考链的原因。
 */
@Slf4j
@Component
public class ReasoningParams {

    /** true = 当前端点按 DeepSeek 协议传参（thinking 对象），false = 按百炼传 enable_thinking */
    private final boolean deepseekStyle;

    public ReasoningParams(@Value("${spring.ai.openai.base-url:}") String baseUrl) {
        this.deepseekStyle = baseUrl != null && baseUrl.contains("deepseek");
        log.info("[ReasoningParams] 思考参数协议 = {}（base-url={}）",
                deepseekStyle ? "deepseek(thinking)" : "dashscope(enable_thinking)", baseUrl);
    }

    /**
     * 构造可直接交给 {@code extraBody} 的参数表。
     *
     * @param thinkingEnabled 是否开启思考链
     */
    public Map<String, Object> extraBody(boolean thinkingEnabled) {
        if (deepseekStyle) {
            // DeepSeek：thinking 必须是对象；enabled/disabled 二态
            return Map.of("thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"));
        }
        return Map.of("enable_thinking", thinkingEnabled);
    }
}
