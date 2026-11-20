package com.healthscience.rag.service;

import com.healthscience.rag.ai.model.ChunkRef;
import com.healthscience.rag.ai.model.ChatTurn;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.entity.ChatMessage;
import com.healthscience.rag.entity.ChatSession;
import com.healthscience.rag.model.dto.ChatDTO;

import java.util.List;
import java.util.Map;

/**
 * 会话与消息的业务能力，对应接口文档 §4.1–§4.5，并为 §4.6 流式问答提供落库支撑。
 *
 * <p><b>纯对话阶段（M4）的取值约定</b>：{@code references} 恒为空列表、
 * {@code retrieveMode} 恒为 {@code "none"} —— 与 RAG 阶段（M6）保持同一套字段，避免后期改契约。
 */
public interface ChatSessionService {

    /** 可返回的最大会话条数（接口文档 §4.2 未要求分页，但需防止长列表拖垮响应） */
    int SESSION_LIST_MAX = 500;

    /** 自动新建会话时，标题截取问题的最大长度 */
    int TITLE_MAX_LEN = 20;

    /** 单页消息条数上限，与接口文档 §4.5 一致 */
    int MESSAGE_PAGE_MAX = 100;

    /** 纯对话阶段的检索策略取值 */
    String RETRIEVE_MODE_NONE = "none";

    /** §4.1 新建会话（恒为普通会话；专属对话只能走 #58，保证唯一性语义单入口） */
    ChatDTO.SessionVO create(Long userId, String title);

    /**
     * §5.4 #58（M16 v4.0）健康对话列表：kind='health'，可多条，updateTime 倒序。
     * 仅供个人健康科普模块；AI 解惑页列表恒过滤 normal。
     */
    List<ChatDTO.SessionVO> listHealthSessions(Long userId);

    /**
     * §5.4 #59（M16 v4.0）创建健康对话（可多条，title 可选）。
     *
     * @throws com.healthscience.rag.common.exception.BizException 健康信息全空（2008）
     */
    ChatDTO.SessionVO createHealthSession(Long userId, String title);

    /** §4.2 会话列表（按更新时间倒序） */
    List<ChatDTO.SessionVO> list(Long userId);

    /** §4.3 重命名会话 */
    void rename(Long userId, Long sessionId, String title);

    /** §4.4 删除会话（逻辑删除，联动删除其下全部消息）；file 会话禁止走此方法（须走 #75） */
    void delete(Long userId, Long sessionId);

    /**
     * 内部删除（不受 kind 限制）：仅供 #75 文档解惑会话删除使用。
     *
     * <p>#75 已在控制器级联清理文件、绑定与落盘，这里只做会话与消息的逻辑删除，
     * 故不再套用「file 必须走解惑页」的守卫（否则 #75 自身会被误拦 400）。
     */
    void deleteInternal(Long userId, Long sessionId);

    /** §4.5 消息列表（按时间正序分页） */
    PageResult<ChatDTO.MessageVO> messages(Long userId, Long sessionId, int pageNum, int pageSize);

    /**
     * 为流式问答解析会话：{@code sessionId} 为空时按问题首句自动新建。
     *
     * @throws com.healthscience.rag.common.exception.BizException 会话不属于当前用户时抛 403
     */
    ChatSession resolveOrCreate(Long userId, Long sessionId, String question);

    /** 落库用户消息（首次提问时调用；「重新生成」不调用，避免重复用户消息） */
    ChatMessage saveUserMessage(Long userId, Long sessionId, String content);

    /**
     * 为「重新生成」做准备：校验目标助手消息归属后逻辑删除它，并返回其对应的原始问题。
     *
     * <p>对应接口文档 §4.6 的 {@code retryFromMessageId} 约定：
     * 不重复落库用户消息，基于同一问题重新生成。
     *
     * <p><b>返回原问题消息 ID 的用途</b>：调用方组装上下文时要排除该消息及其之后的内容，
     * 否则原问题既出现在历史里、又作为本轮提问，模型会看到重复提问。
     */
    ChatDTO.RetryContext prepareRetry(Long userId, Long sessionId, Long retryFromMessageId);

    /**
     * 组装会话上下文：从 {@code chat_message} 取最近若干条，按字符预算从最新往前裁剪。
     *
     * <p><b>为什么由服务端组装而不是客户端提交</b>：
     * <ol>
     *   <li><b>安全</b>：客户端可提交任意「历史」，包括伪造的对话甚至夹带指令
     *       —— 这是典型的 prompt injection 入口；服务端组装后该入口消失。</li>
     *   <li><b>一致</b>：多端（Web / 未来小程序）拿到的上下文口径一致。</li>
     *   <li><b>经济</b>：字符预算在服务端收敛，避免客户端把整段长会话塞进请求体。</li>
     * </ol>
     *
     * @param sessionId       会话 ID
     * @param maxChars        字符预算（中文约 1 字 ≈ 1 token）
     * @param beforeMessageId 只取该消息<b>之前</b>的内容；传 null 表示取全部最近消息
     * @return 按时间<b>正序</b>的历史
     */
    List<ChatTurn> buildHistory(Long sessionId, int maxChars, Long beforeMessageId);

    /**
     * 落库助手消息，返回带主键的实体（供 done 事件回传 messageId）。
     *
     * @param queryText  实际用于检索的改写后查询；未检索（none 模式/纯对话）传 null
     * @param isRejected 拒答/敏感拦截（02 §4.6：error 2002/2004 的回答照常落库并标记）
     * @param answerSource 对照解答来源（B-27）：global/fused；null = 普通单答案
     * @param conflictGroupId 对照解答组 ID（B-27）：双答案两条共享；null = 普通单答案
     */
    ChatMessage saveAssistantMessage(Long userId,
                                     Long sessionId,
                                     String content,
                                     Integer latencyMs,
                                     Integer firstTokenMs,
                                     Integer finishStatus,
                                     String retrieveMode,
                                     String queryText,
                                     boolean isRejected,
                                     List<ChunkRef> references,
                                     String answerSource,
                                     String conflictGroupId);

    /** 触摸会话的更新时间（每次问答结束时调用，影响列表排序） */
    void touch(Long sessionId);

    /**
     * 落库检索过程回放数据（B-23，§6.10 数据源）：随助手消息写 {@code trace_json}。
     * trace 为 null/空时跳过（纯对话、NONE 模式无检索过程）。
     */
    void saveTrace(Long messageId, Map<String, Object> trace);

    /**
     * §4.9 追问建议：优先返回 {@code suggestions_json} 缓存；未缓存时调用
     * SuggestionService 生成并写回缓存（仅缓存成功结果，失败下次重试）。
     *
     * @throws com.healthscience.rag.common.exception.BizException 消息不存在 / 非本人 / 非助手消息（404）
     */
    java.util.List<String> suggestions(Long userId, Long messageId);

    /**
     * §4.7 对某条助手消息提交反馈。
     *
     * <p>同一用户对同一消息重复提交时<b>覆盖</b>原反馈（而不是新增一条），
     * 否则用户改主意会产生多条互相矛盾的记录，管理端无法处理。
     *
     * @throws com.healthscience.rag.common.exception.BizException 消息不存在、不属于当前用户或类型非法
     */
    void addFeedback(Long userId, Long messageId, String type, String comment);
}
