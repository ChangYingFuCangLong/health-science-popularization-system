package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.EvalScoringService;
import com.healthscience.rag.ai.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评测生成与判分的百炼实现（02 §八 B-22）。
 *
 * <p><b>两处刻意的设计</b>：
 * <ol>
 *   <li>温度恒为 {@code 0}（生成与判分同）：评测要的是可复现，不是多样性；</li>
 *   <li>模型通过 {@link ObjectProvider} 取，缺失时安静降级为 {@code null} / {@code ok=false}，
 *       绝不让评测跑批因为模型不可用而整批失败——失败只影响单条分数的有無。</li>
 * </ol>
 *
 * <p><b>与 {@link RagChatService} 的区别</b>：不检索、不过安全闸门、不落库、不写 trace，
 * 纯粹"给定资料 → 生成"和"给定资料与答案 → 打分"。
 */
@Slf4j
@Service
public class DashScopeEvalScoringService implements EvalScoringService {

    /** 生成阶段：要求答案忠实于资料并标注引用编号，与线上问答同口径 */
    private static final String GENERATE_SYSTEM = """
            你是健康科普问答系统的作答模块。请严格依据「参考资料」回答问题，要求：
            1. 只使用参考资料中的信息，资料未覆盖的部分明确说明"资料未涉及"，不得自行补充医学结论；
            2. 引用资料的句子在句末标注编号，如 [1]，编号对应资料条目的序号；
            3. 用通俗易懂的中文，先给结论再展开，控制在 300 字以内。
            """;

    /** 判分阶段：rubric 固定，输出严格 JSON，便于程序化解析 */
    private static final String JUDGE_SYSTEM = """
            你是健康科普问答的评分员。请依据「参考资料」评判「待评答案」，只输出一个 JSON 对象，不要任何解释文字。

            评分项：
            1. faithfulness（1~5 整数）——答案中的事实性陈述能否在参考资料中找到依据：
               5 = 全部有依据；4 = 绝大部分有依据；3 = 约一半有依据；2 = 少量有依据；1 = 基本无依据或与资料矛盾。
            2. citationCorrect（0 或 1）——答案中标注的引用编号是否确实对应支撑该句的参考条目：
               1 = 引用与支撑内容一致；0 = 存在引用错位、编号不存在或引用内容与论述无关。

            参考标准答案（可能为空，仅作语义参照，不作为打分依据）：
            %s

            输出格式（严格照此，不加 Markdown 代码块）：
            {"faithfulness": 4, "citationCorrect": 1}
            """;

    private static final Pattern JSON_OBJ = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern FAITH = Pattern.compile("\"faithfulness\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern CITATION = Pattern.compile("\"citationCorrect\"\\s*:\\s*([01])");

    private final ObjectProvider<OpenAiChatModel> chatModelProvider;
    private final boolean thinkingEnabled;
    private final ReasoningParams reasoningParams;

    public DashScopeEvalScoringService(
            ObjectProvider<OpenAiChatModel> chatModelProvider,
            @org.springframework.beans.factory.annotation.Value("${health.ai.thinking-enabled:false}")
            boolean thinkingEnabled,
            ReasoningParams reasoningParams) {
        this.chatModelProvider = chatModelProvider;
        this.thinkingEnabled = thinkingEnabled;
        this.reasoningParams = reasoningParams;
    }

    @Override
    public String generate(String question, List<RetrievedChunk> chunks) {
        OpenAiChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            log.warn("[EvalScoring] 模型不可用，跳过生成：{}", question);
            return null;
        }
        try {
            Prompt prompt = new Prompt(
                    List.of(new SystemMessage(GENERATE_SYSTEM),
                            new UserMessage(buildUserMessage(question, chunks))),
                    zeroTemperature());
            String text = model.call(prompt).getResult().getOutput().getText();
            return text == null || text.isBlank() ? null : text.trim();
        } catch (RuntimeException e) {
            log.warn("[EvalScoring] 生成失败，跳过该条：question={}, err={}", question, e.getMessage());
            return null;
        }
    }

    @Override
    public JudgeResult judge(String question, String groundTruth, String answer,
                             List<RetrievedChunk> chunks) {
        OpenAiChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            return JudgeResult.fail("模型不可用");
        }
        if (answer == null || answer.isBlank()) {
            return JudgeResult.fail("答案为空，无从判分");
        }
        try {
            String system = JUDGE_SYSTEM.formatted(
                    groundTruth == null || groundTruth.isBlank() ? "（空）" : groundTruth);
            String user = buildUserMessage(question, chunks)
                    + "\n\n待评答案：\n" + answer;
            Prompt prompt = new Prompt(
                    List.of(new SystemMessage(system), new UserMessage(user)),
                    zeroTemperature());
            String raw = model.call(prompt).getResult().getOutput().getText();
            return parse(raw);
        } catch (RuntimeException e) {
            log.warn("[EvalScoring] 判分调用失败：question={}, err={}", question, e.getMessage());
            return JudgeResult.fail("调用异常: " + e.getMessage());
        }
    }

    // ---------- 内部 ----------

    private OpenAiChatOptions zeroTemperature() {
        return OpenAiChatOptions.builder()
                .temperature(0.0)
                // 判分与生成都不要思考链：推理帧既拖长耗时，也可能污染 JSON 输出
                .extraBody(reasoningParams.extraBody(thinkingEnabled))
                .build();
    }

    /** 参考资料 + 问题；资料为空时显式告知模型"无资料"，避免它假装有依据 */
    private String buildUserMessage(String question, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        if (chunks == null || chunks.isEmpty()) {
            sb.append("参考资料：（本次未检索到任何资料）\n\n");
        } else {
            sb.append("参考资料：\n");
            for (int i = 0; i < chunks.size(); i++) {
                sb.append('[').append(i + 1).append("] ");
                String meta = buildMetaLine(chunks.get(i));
                if (!meta.isEmpty()) {
                    sb.append(meta).append('\n');
                }
                sb.append(chunks.get(i).content() == null ? "" : chunks.get(i).content().trim())
                        .append("\n\n");
            }
        }
        sb.append("问题：").append(question);
        return sb.toString();
    }

    /** 参考资料出处一行（有则显示，无则空），让模型判"引用是否对得上"时有据可依 */
    private String buildMetaLine(RetrievedChunk c) {
        String title = c.meta("title");
        String section = c.meta("section");
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) {
            sb.append("出自《").append(title).append('》');
        }
        if (!section.isEmpty()) {
            sb.append(" · ").append(section);
        }
        return sb.toString();
    }

    /** 容错解析：模型偶尔包一层代码块或加解释，取第一个 {...} 再正则抽两个字段 */
    private JudgeResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return JudgeResult.fail("模型返回空");
        }
        Matcher obj = JSON_OBJ.matcher(raw);
        if (!obj.find()) {
            return JudgeResult.fail("返回不含 JSON: " + abbreviate(raw));
        }
        String json = obj.group();
        Matcher f = FAITH.matcher(json);
        Matcher c = CITATION.matcher(json);
        if (!f.find() || !c.find()) {
            return JudgeResult.fail("JSON 缺字段: " + abbreviate(json));
        }
        BigDecimal faith = new BigDecimal(f.group(1)).setScale(2, RoundingMode.HALF_UP);
        // rubric 是 1~5 整数，模型给出越界值时夹紧而不是整条丢弃
        if (faith.compareTo(BigDecimal.ONE) < 0) {
            faith = BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP);
        } else if (faith.compareTo(new BigDecimal("5")) > 0) {
            faith = new BigDecimal("5").setScale(2, RoundingMode.HALF_UP);
        }
        return JudgeResult.of(faith, Integer.parseInt(c.group(1)));
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
