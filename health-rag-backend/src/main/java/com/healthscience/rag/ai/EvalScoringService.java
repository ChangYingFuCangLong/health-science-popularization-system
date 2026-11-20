package com.healthscience.rag.ai;

import com.healthscience.rag.ai.model.RetrievedChunk;

import java.math.BigDecimal;
import java.util.List;

/**
 * 评测专用的生成与判分能力（02 §八 B-22）。
 *
 * <p><b>为什么不复用 {@link ChatService}</b>：评测必须"用指定策略检索到的块"生成答案，
 * 而 {@code ChatService.chat()} 走的是各实现自己的检索路径（RAG 实现会再检索一次），
 * 策略不一致就失去消融实验的可比性。本接口只接受调用方已检索好的块，
 * 保证「检索策略 → 生成依据」严格绑定。
 *
 * <p><b>为什么放在 ai 包</b>：实现需要直连模型客户端（judge 温度必须为 0），
 * 而 ArchUnit 约束 C1/C2 规定 {@code org.springframework.ai..} 只允许出现在 ai 包内。
 * 业务层（{@code service}）只依赖本接口，不接触任何框架类型。
 */
public interface EvalScoringService {

    /**
     * 基于给定检索块生成答案。
     *
     * @param question 评测问题
     * @param chunks   该题在该策略下检索到的块（可为空列表）
     * @return 答案文本；模型不可用或调用失败时返回 {@code null}，由调用方跳过该条
     */
    String generate(String question, List<RetrievedChunk> chunks);

    /**
     * LLM-as-judge 判分：温度 0 + 固定 rubric。
     *
     * <p>rubric 口径（写进 Prompt，论文方法学章节同口径声明）：
     * <ul>
     *   <li>{@code faithfulness} 1~5：答案中的事实性陈述能否在参考资料中找到依据。
     *       5=全部有依据；4=绝大部分有依据；3=一半左右；2=少量；1=基本无依据或与资料冲突。</li>
     *   <li>{@code citationCorrect} 0/1：答案标注的引用编号是否确实对应支撑该句的参考块。</li>
     * </ul>
     *
     * @param question    评测问题
     * @param groundTruth 标注答案（可为 {@code null} 或空）
     * @param answer      被测系统生成的答案
     * @param chunks      生成时依据的检索块（判分依据）
     * @return 判分结果；失败时 {@code ok=false}，调用方不应写入任何分数
     */
    JudgeResult judge(String question, String groundTruth, String answer, List<RetrievedChunk> chunks);

    /**
     * 判分结果。
     *
     * <p>{@code ok=false} 表示本次未成功打分，此时两个分数字段为 {@code null} 且无意义；
     * {@code reason} 记录失败原因，供日志排查。
     */
    record JudgeResult(BigDecimal faithfulness, Integer citationCorrect, boolean ok, String reason) {

        public static JudgeResult fail(String reason) {
            return new JudgeResult(null, null, false, reason);
        }

        public static JudgeResult of(BigDecimal faithfulness, int citationCorrect) {
            return new JudgeResult(faithfulness, citationCorrect, true, null);
        }
    }
}
