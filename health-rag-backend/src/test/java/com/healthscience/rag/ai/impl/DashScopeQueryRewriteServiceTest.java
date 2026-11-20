package com.healthscience.rag.ai.impl;

import org.springframework.ai.chat.model.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查询改写降级纪律单测（M10/B-12）。
 *
 * <p>核心约束：改写绝不能影响主链路 —— 关闭/失败/输出异常一律原样返回当前问题。
 */
@ExtendWith(MockitoExtension.class)
class DashScopeQueryRewriteServiceTest {

    @Mock
    private OpenAiChatModel chatModel;

    private static final String QUESTION = "那它怎么预防？";

    /** 思考参数适配器：本单测只关心降级纪律，不校验协议差异，按 DeepSeek 端点构造即可 */
    private static final ReasoningParams PARAMS = new ReasoningParams("https://api.deepseek.com");

    @Test
    @DisplayName("开关关闭：不调模型，原样返回")
    void disabledReturnsQuestion() {
        DashScopeQueryRewriteService svc = new DashScopeQueryRewriteService(chatModel, false, PARAMS);

        assertThat(svc.rewrite(null, QUESTION)).isEqualTo(QUESTION);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("模型调用异常：降级为原问题，不抛出")
    void failureFallsBackToQuestion() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        DashScopeQueryRewriteService svc = new DashScopeQueryRewriteService(chatModel, true, PARAMS);

        assertThat(svc.rewrite(null, QUESTION)).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("正常改写：返回模型输出（去空白）")
    void normalRewrite() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("高血压怎么预防")))));
        DashScopeQueryRewriteService svc = new DashScopeQueryRewriteService(chatModel, true, PARAMS);

        assertThat(svc.rewrite(null, QUESTION)).isEqualTo("高血压怎么预防");
    }

    @Test
    @DisplayName("输出异常（含换行/过长）：降级为原问题")
    void abnormalOutputFallsBack() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(
                                "改写结果\n第二行".repeat(30))))));
        DashScopeQueryRewriteService svc = new DashScopeQueryRewriteService(chatModel, true, PARAMS);

        assertThat(svc.rewrite(null, QUESTION)).isEqualTo(QUESTION);
    }
}
