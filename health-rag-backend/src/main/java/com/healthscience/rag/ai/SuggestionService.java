package com.healthscience.rag.ai;

import java.util.List;

/**
 * 追问建议能力接口（框架无关，任务卡 B-14，契约 02 §4.9）。
 *
 * <p>基于一条助手回答与其引用来源，生成用户可能继续追问的 3~4 个问题。
 * 缓存由业务层负责（{@code chat_message.suggestions_json}），本接口只负责生成。
 */
public interface SuggestionService {

    /**
     * 生成追问建议。
     *
     * @param answer          助手回答全文
     * @param referenceTitles 回答引用的来源标题（去重后，可为空）
     * @return 3~4 条追问问题；失败时返回空列表（业务层不缓存空结果，下次可重试）
     */
    List<String> suggest(String answer, List<String> referenceTitles);
}
