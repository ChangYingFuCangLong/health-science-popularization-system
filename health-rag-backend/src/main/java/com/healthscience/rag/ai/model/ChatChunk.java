package com.healthscience.rag.ai.model;

import java.util.List;
import java.util.Map;

/**
 * 流式片段：AI 层向编排层推送的结构化事件。
 *
 * <p><b>为什么不是 {@code Flux<String>}</b>（ADR-004 修订）：{@code Reference} 是结构化数组、
 * {@code Done} 含统计字段。若统一用字符串承载，只能序列化后再由 Controller 反序列化，
 * 契约会退化成「字符串协议」，AI 接口层的抽象随即失去意义。
 *
 * <p><b>职责边界</b>：{@code Reference} / {@code Delta} / {@code Error} 由 {@code ChatService}
 * 实现类产出；{@code Done} 由 <b>Controller 组装</b>（它依赖落库后的 {@code messageId}
 * 与耗时统计，AI 层无从得知）。
 *
 * <p><b>协议适配的唯一归属</b>：{@code ChatChunk} → SSE 事件文本的序列化只在 Controller 发生。
 */
public sealed interface ChatChunk {

    /** 回答开始前推送的引用片段。纯对话阶段推空列表；B-27 冲突时带 conflict */
    record Reference(List<ChunkRef> chunks, String rewrittenQuery,
                     java.util.Map<String, Object> conflict) implements ChatChunk {

        public static Reference empty() {
            return new Reference(List.of(), "", null);
        }
    }

    /** LLM 增量文本（B-27：answerId 仅对照解答模式，缺省 null = 单答案） */
    record Delta(String content, String answerId) implements ChatChunk {

        public Delta(String content) {
            this(content, null);
        }
    }

    /** 生成结束，携带完整回答与统计信息 */
    record Done(DoneInfo info) implements ChatChunk {
    }

    /** 出错或拒答（{@code code = 2002} 表示知识库中无相关内容） */
    record Error(int code, String message) implements ChatChunk {
    }

    /**
     * 链路阶段信号（任务卡 B-13，契约 02 §4.6）：
     * {@code retrieve_start} → {@code retrieve_done}（携带改写后检索词）→ {@code generate_start}。
     * 纯增益事件：前端未消费时行为不变。仅 RAG 编排层产出。
     */
    record Stage(String name, String rewrittenQuery) implements ChatChunk {

        public Stage(String name) {
            this(name, null);
        }
    }

    /**
     * 检索过程明细（任务卡 B-23，§6.10 检索回放的数据源）。
     * <b>不对外推送</b>：Controller 捕获后随消息落库 {@code chat_message.trace_json}，
     * 回放走管理端只读接口——把它放进事件流而非直接落库，是为了让 AI 层
     * 不接触任何存储类型（C1/C2）。
     */
    record Trace(java.util.Map<String, Object> payload) implements ChatChunk {
    }

    /** 对照解答开始（B-27，契约 07 §2.3）：source=global 系统答案 / fused 融合答案 */
    record AnswerStart(int answerId, String source, String label) implements ChatChunk {
    }

    /** 对照解答结束（B-27）：messageId 由 Controller 保存后填入 answer_end SSE */
    record AnswerEnd(int answerId) implements ChatChunk {
    }

    /** 健康元数据（B-28 二次完善）：矛盾提醒/引导语，Controller 捕获后并入 done，不对外推送 */
    record HealthMeta(Map<String, Object> healthReminder, String healthSuggestion) implements ChatChunk {
    }
}
