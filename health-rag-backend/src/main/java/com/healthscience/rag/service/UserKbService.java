package com.healthscience.rag.service;

import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.model.dto.UserKbDTO;

/**
 * 用户个人知识库（M15，契约 07，#44–#52）。
 *
 * <p><b>隔离纪律</b>：所有方法只作用于 {@code owner_user_id = 当前用户} 的数据；
 * 越权一律 404（不暴露资源是否存在，防 ID 枚举探测）。
 * <b>隐私</b>：个人库内容对管理端不可见（07 决策 D3）。
 */
public interface UserKbService {

    /** #44 概览与配额 */
    UserKbDTO.StatsVO stats(Long userId);

    /** #45 文档列表（分页 + 标题关键词 + 状态筛选） */
    PageResult<UserKbDTO.DocumentVO> documents(Long userId, String keyword, Integer status,
                                               int pageNum, int pageSize);

    /**
     * #46 上传并触发处理：配额（文档数/总大小）与格式/大小校验<b>在落盘之前</b>，
     * 总块数配额在处理中校验（超限置失败态）。
     */
    Long upload(Long userId, String originalFilename, byte[] content, String title);

    /** #47 处理进度（owner 校验，越权 404） */
    UserKbDTO.ProgressVO progress(Long userId, Long documentId);

    /** #48 分块预览（分页，owner 校验） */
    PageResult<UserKbDTO.ChunkPreviewVO> chunks(Long userId, Long documentId, int pageNum, int pageSize);

    /** #49 删除（级联同全局库 + 清理落盘文件；物理释放磁盘；事务化） */
    void delete(Long userId, Long documentId);

    /** #50 单文档重新向量化（与全局重建共用互斥，避免争抢 Ollama） */
    void rebuild(Long userId, Long documentId);

    /** #51 读取偏好（enabled 为派生：有 ≥1 个完成文档即 true） */
    UserKbDTO.SettingsVO settings(Long userId);

    /** #52 更新偏好 */
    void updateSettings(Long userId, UserKbDTO.SettingsUpdateRequest request);

    /** 个人库是否有可检索内容（status=2 的文档 ≥1）——问答页 scope 开关与 2002 快速拒答用 */
    boolean hasContent(Long userId);

    /** 个人资料是否参与问答（默认 true；false 时服务端强制忽略 personal 成分） */
    boolean includeInChat(Long userId);
}
