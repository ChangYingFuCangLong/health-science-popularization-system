package com.healthscience.rag.service.impl;

import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.entity.ChatMessage;
import com.healthscience.rag.mapper.ChatMessageMapper;
import com.healthscience.rag.mapper.ChatSessionMapper;
import com.healthscience.rag.mapper.MessageFeedbackMapper;
import com.healthscience.rag.model.dto.ChatDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话业务单测（M9/B-7）。
 *
 * <p>重点覆盖 B-1 修复的 prepareRetry 语义（校验先行、只删助手消息）与
 * buildHistory 的字符预算裁剪 —— 这两处是流式问答正确性的关键路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatSessionServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 10L;

    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private ChatMessageMapper messageMapper;
    @Mock
    private MessageFeedbackMapper feedbackMapper;
    @Mock
    private org.springframework.beans.factory.ObjectProvider<com.healthscience.rag.ai.SuggestionService> suggestionProvider;
    @Mock
    private com.healthscience.rag.service.UserPortraitService userPortraitService;

    private ChatSessionServiceImpl service;

    @BeforeAll
    static void initMpTableInfo() {
        // LambdaUpdateWrapper 依赖 MP 的表信息缓存；单测无 MP 运行时，手工初始化
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.healthscience.rag.entity.ChatMessage.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChatSessionServiceImpl(sessionMapper, messageMapper, feedbackMapper,
                new com.fasterxml.jackson.databind.ObjectMapper(), suggestionProvider, userPortraitService);
        org.mockito.Mockito.when(suggestionProvider.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.when(userPortraitService.hasContent(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(false);
    }

    private ChatMessage message(Long id, String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setSessionId(SESSION_ID);
        m.setUserId(USER_ID);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    // ---------- prepareRetry（B-1 修复语义） ----------

    @Test
    @DisplayName("重新生成：目标必须是助手消息，传用户消息 ID → 403 且不删除")
    void prepareRetryRejectsUserMessage() {
        when(messageMapper.selectById(5L)).thenReturn(message(5L, "user", "提问"));

        assertThatThrownBy(() -> service.prepareRetry(USER_ID, SESSION_ID, 5L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(403);
        verify(messageMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("重新生成：他人消息 → 403")
    void prepareRetryRejectsForeignMessage() {
        ChatMessage foreign = message(5L, "assistant", "回答");
        foreign.setUserId(99L);
        when(messageMapper.selectById(5L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.prepareRetry(USER_ID, SESSION_ID, 5L))
                .isInstanceOf(BizException.class);
        verify(messageMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("重新生成：找不到原问题 → 400 且目标消息未被误删（B-1 先验后删）")
    void prepareRetryWithoutPriorQuestionDoesNotDelete() {
        when(messageMapper.selectById(5L)).thenReturn(message(5L, "assistant", "回答"));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.prepareRetry(USER_ID, SESSION_ID, 5L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);
        verify(messageMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("重新生成：成功路径返回原问题与原问题 ID，并删除目标消息")
    void prepareRetryHappyPath() {
        when(messageMapper.selectById(9L)).thenReturn(message(9L, "assistant", "旧回答"));
        when(messageMapper.selectList(any())).thenReturn(List.of(message(5L, "user", "原始提问")));

        ChatDTO.RetryContext ctx = service.prepareRetry(USER_ID, SESSION_ID, 9L);

        assertThat(ctx.question()).isEqualTo("原始提问");
        assertThat(ctx.questionMessageId()).isEqualTo(5L);
        verify(messageMapper).deleteById(eq(9L));
    }

    // ---------- buildHistory ----------

    @Test
    @DisplayName("上下文组装：按字符预算从最新往前裁剪，输出正序")
    void buildHistoryCutsByBudget() {
        // selectList 返回 id 倒序（最新在前）：30 + 30 字都在预算 100 内，2000 字的超限被丢弃
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(30L, "user", "三".repeat(30)),
                message(20L, "assistant", "二".repeat(30)),
                message(10L, "user", "一".repeat(2000))));

        List<com.healthscience.rag.ai.model.ChatTurn> turns =
                service.buildHistory(SESSION_ID, 100, null);

        assertThat(turns).hasSize(2)
                .extracting(com.healthscience.rag.ai.model.ChatTurn::content)
                .containsExactly("二".repeat(30), "三".repeat(30));
    }

    @Test
    @DisplayName("上下文组装：至少保留一轮，首条超预算也放入")
    void buildHistoryKeepsAtLeastOneTurn() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(10L, "user", "长".repeat(2000))));

        List<com.healthscience.rag.ai.model.ChatTurn> turns =
                service.buildHistory(SESSION_ID, 100, null);

        assertThat(turns).hasSize(1);
    }

    // ---------- addFeedback ----------

    @Test
    @DisplayName("反馈：非法类型 → 400")
    void addFeedbackRejectsBadType() {
        assertThatThrownBy(() -> service.addFeedback(USER_ID, 9L, "meh", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("反馈：重复提交覆盖原反馈而不是新增一条")
    void addFeedbackOverwritesExisting() {
        when(messageMapper.selectById(9L)).thenReturn(message(9L, "assistant", "回答"));
        com.healthscience.rag.entity.MessageFeedback existing = new com.healthscience.rag.entity.MessageFeedback();
        existing.setId(77L);
        existing.setMessageId(9L);
        existing.setUserId(USER_ID);
        existing.setType("useful");
        when(feedbackMapper.selectOne(any())).thenReturn(existing);

        service.addFeedback(USER_ID, 9L, "wrong", "内容有误");

        verify(feedbackMapper).updateById(argThat((com.healthscience.rag.entity.MessageFeedback fb) ->
                "wrong".equals(fb.getType())));
        verify(feedbackMapper, never()).insert(any(com.healthscience.rag.entity.MessageFeedback.class));
    }

    @Test
    @DisplayName("反馈：目标消息不属于本人 → 403")
    void addFeedbackRejectsForeignMessage() {
        ChatMessage foreign = message(9L, "assistant", "回答");
        foreign.setUserId(99L);
        when(messageMapper.selectById(9L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.addFeedback(USER_ID, 9L, "useful", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权限");
    }

    // ---------- suggestions（§4.9） ----------

    @Test
    @DisplayName("建议：非助手消息 → 404")
    void suggestionsRejectUserMessage() {
        when(messageMapper.selectById(5L)).thenReturn(message(5L, "user", "提问"));

        assertThatThrownBy(() -> service.suggestions(USER_ID, 5L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("建议：命中缓存直接返回，不调 LLM")
    void suggestionsReturnCached() {
        ChatMessage cached = message(9L, "assistant", "回答");
        cached.setSuggestionsJson("[\"追问一\",\"追问二\"]");
        when(messageMapper.selectById(9L)).thenReturn(cached);
        when(suggestionProvider.getIfAvailable())
                .thenReturn((answer, titles) -> { throw new IllegalStateException("不应被调用"); });

        assertThat(service.suggestions(USER_ID, 9L)).containsExactly("追问一", "追问二");
    }

    @Test
    @DisplayName("建议：未缓存时生成并写回缓存")
    void suggestionsGenerateAndCache() {
        when(messageMapper.selectById(9L)).thenReturn(message(9L, "assistant", "回答全文"));
        when(suggestionProvider.getIfAvailable())
                .thenReturn((answer, titles) -> List.of("怎么预防？", "什么情况要就医？"));

        assertThat(service.suggestions(USER_ID, 9L)).containsExactly("怎么预防？", "什么情况要就医？");
        verify(messageMapper).update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("建议：建议组件未装配（非 dashscope provider）返回空列表")
    void suggestionsEmptyWhenProviderAbsent() {
        when(messageMapper.selectById(9L)).thenReturn(message(9L, "assistant", "回答"));

        assertThat(service.suggestions(USER_ID, 9L)).isEmpty();
    }
}
