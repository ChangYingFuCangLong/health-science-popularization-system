package com.healthscience.rag.service;

import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.model.dto.AdminDTO;

import java.util.List;
import java.util.Map;

/**
 * 管理端能力，对应接口文档 §6。
 *
 * <p><b>权限</b>：调用方（Controller）必须先校验 {@code role = ADMIN}，
 * 本层不再重复校验 —— 但所有方法都假定调用者已鉴权。
 */
public interface AdminService {

    /** §6.2 文档列表 */
    PageResult<KbDocument> documents(Integer status, String category, String keyword,
                                     int pageNum, int pageSize);

    /** §6.5 文档分块 */
    List<AdminDTO.ChunkVO> documentChunks(Long documentId);

    /** §6.7 向量索引元信息 */
    AdminDTO.VectorMetaVO vectorMeta();

    /** §6.8 数据看板 */
    AdminDTO.OverviewVO overview();

    /**
     * §6.10 检索过程回放（M14/B-23）：读取助手消息的 {@code trace_json}。
     *
     * @throws com.healthscience.rag.common.exception.BizException 消息不存在 / 非助手消息 / 无 trace（404）
     */
    Map<String, Object> retrievalTrace(Long messageId);

    /** §6.9 问答日志（以助手回答为主体，回溯其提问） */
    PageResult<AdminDTO.ChatLogVO> chatLogs(Long userId, Integer isRejected,
                                            String startDate, String endDate,
                                            int pageNum, int pageSize);

    /** §6.10 反馈列表 */
    PageResult<AdminDTO.FeedbackVO> feedbacks(Integer status, int pageNum, int pageSize);

    /** §6.11 用户列表（不含密码哈希） */
    /**
     * 用户列表（F1：支持用户名/昵称模糊搜索，keyword 为空时等价于全量分页）
     */
    PageResult<AdminDTO.UserVO> users(int pageNum, int pageSize, String keyword);

    /**
     * §6.10 处理反馈：按传入 status 落值（0 待处理 / 1 已处理）。
     * 置为已处理时记录操作人与时间，改回待处理时清空二者。
     */
    void markFeedbackHandled(Long feedbackId, Integer status, Long operatorId);

    /** §6.11 启用 / 禁用用户 */
    void updateUserStatus(Long userId, Integer status, Long operatorId);

    /** §6.3 删除文档：连同分块与向量一起删除 */
    void deleteDocument(Long documentId);

    /**
     * §6.6 全量重建向量索引：清空旧向量 → 重新向量化全部分块 → 更新元信息。
     *
     * <p>耗时随分块数线性增长（2391 块约 2~10 分钟，取决于本地还是云端 embedding），
     * 调用方应异步执行，不要放在请求线程里同步跑完。
     */
    void rebuildAllVectors();

    /** §6.4 重建单个文档的索引 */
    void rebuildDocument(Long documentId);

    /**
     * §6.2 上传文档（M12/B-19）：校验 → 落盘 → 建档（status=1）→ 异步解析分块向量化。
     *
     * @return 新文档 ID（处理进度走 {@link #documentProgress}
     * @throws com.healthscience.rag.common.exception.BizException 校验失败（400）
     */
    Long uploadDocument(String originalFilename, byte[] content,
                        String title, String category, String source, Long operatorId);

    /**
     * §6.3 处理进度（M12/B-20）：status + 已入库块数 + 预估总块数。
     * chunksTotal 仅处理中可预估（内存值，重启丢失）；完成/失败时为 null（用 chunk_count 查询即可）。
     */
    AdminDTO.DocumentProgressVO documentProgress(Long documentId);

    /** 当前向量索引是否处于「构建中」，用于拒绝并发重建 */
    boolean isRebuilding();
}
