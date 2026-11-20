package com.healthscience.rag.ai;

import com.healthscience.rag.ai.model.ChatTurn;

import java.util.List;

/**
 * 查询改写能力接口（框架无关，任务卡 B-12）。
 *
 * <p><b>解决什么问题</b>：多轮对话中"那怎么预防？"这类指代型追问若原样送去检索，
 * 向量与关键词两路都召不回正确内容 —— 这是多轮 RAG 的标准痛点。
 * 改写 = 基于最近历史把追问重写为<b>独立、自包含</b>的检索 query。
 *
 * <p><b>降级约定</b>：实现类在任何失败（开关关闭 / LLM 超时 / 输出异常）时
 * 必须原样返回当前问题 —— 改写是增强手段，绝不让主链路失败。
 *
 * <p><b>实验价值</b>：{@code health.rag.rewrite-enabled} 开/关是论文消融变量
 * （预期显著提升多轮子集的 HitRate@5）。
 */
public interface QueryRewriteService {

    /**
     * 改写检索查询。
     *
     * @param history  会话历史（正序，实现类只取最近几轮）
     * @param question 本轮提问
     * @return 改写后的检索 query；失败时原样返回 question
     */
    String rewrite(List<ChatTurn> history, String question);
}
