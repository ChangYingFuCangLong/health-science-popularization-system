package com.healthscience.rag.service;

import com.healthscience.rag.model.dto.AuthDTO;

/**
 * 账号注销（M16 v4.0，B-25/B-26，契约 #44/#45）。
 *
 * <p><b>联动清理全清单</b>（02 §十一，个人资产全量清理，审计数据保留）：
 * 健康五表（health_metric / metric_def 自定义 / health_kv / user_portrait /
 * chat_health_fact）+ 个人资料库（scope='personal' 文档/分块/向量/落盘 user-kb/）+
 * 文档解惑（qa_session_document + scope='file' 资产 + 其会话消息）；
 * 普通会话/消息保留（审计），username 置「已注销用户」。
 */
public interface AccountService {

    /**
     * §三 #45 自助注销：body {password}（1004 校验）；ADMIN 不可注销 400。
     * 成功后该用户全部旧 token 失效（token_invalid_before）。
     */
    void deactivateSelf(Long userId, AuthDTO.DeactivateRequest request);

    /**
     * §七 #44 管理端删除用户：不存在 404；删自己/删 ADMIN 400。
     * 副作用同 #45（同一张清理清单）。
     */
    void deleteByAdmin(Long targetUserId, Long operatorId);
}
