package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.entity.ChatSession;
import com.healthscience.rag.entity.HealthKv;
import com.healthscience.rag.entity.HealthMetric;
import com.healthscience.rag.entity.KbChunk;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.entity.MetricDef;
import com.healthscience.rag.entity.SysUser;
import com.healthscience.rag.entity.UserPortrait;
import com.healthscience.rag.mapper.ChatHealthFactMapper;
import com.healthscience.rag.mapper.ChatSessionMapper;
import com.healthscience.rag.mapper.HealthKvMapper;
import com.healthscience.rag.mapper.HealthMetricMapper;
import com.healthscience.rag.mapper.KbChunkMapper;
import com.healthscience.rag.mapper.KbDocumentMapper;
import com.healthscience.rag.mapper.MetricDefMapper;
import com.healthscience.rag.mapper.QaSessionDocumentMapper;
import com.healthscience.rag.mapper.SysUserMapper;
import com.healthscience.rag.mapper.UserPortraitMapper;
import com.healthscience.rag.model.dto.AuthDTO;
import com.healthscience.rag.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号注销实现（B-25/B-26）。整个清理在一个事务里（@Transactional）：
 * 任一步失败整体回滚——绝不允许"删了一半"的账号残留。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final HealthMetricMapper metricMapper;
    private final MetricDefMapper metricDefMapper;
    private final HealthKvMapper kvMapper;
    private final UserPortraitMapper portraitMapper;
    private final ChatHealthFactMapper factMapper;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final ChatSessionMapper sessionMapper;
    private final com.healthscience.rag.mapper.ChatMessageMapper messageMapper;
    private final QaSessionDocumentMapper qaDocMapper;

    @Value("${health.rag.upload-dir:./data/uploads}")
    private String uploadDir;

    @Override
    @Transactional
    public void deactivateSelf(Long userId, AuthDTO.DeactivateRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        if (request == null || request.password() == null
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException(ErrorCode.WRONG_OLD_PASSWORD, "密码错误");
        }
        if ("ADMIN".equals(user.getRole())) {
            throw BizException.badRequest("管理员账号不可注销");
        }
        wipePersonalData(user);
    }

    @Override
    @Transactional
    public void deleteByAdmin(Long targetUserId, Long operatorId) {
        if (targetUserId.equals(operatorId)) {
            throw BizException.badRequest("不能删除当前登录账号");
        }
        SysUser user = userMapper.selectById(targetUserId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        if ("ADMIN".equals(user.getRole())) {
            throw BizException.badRequest("不能删除管理员账号");
        }
        wipePersonalData(user);
    }

    /**
     * 注销/删除共用清理（02 §十一 联动全清单）：
     * ① 健康五表物理删；② 个人库+文档解惑资产（分块向量置 NULL→逻辑删，落盘清理）；
     * ③ file 会话与消息逻辑删；④ 普通会话保留但 username 置空；⑤ 用户逻辑删+踢下线。
     */
    private void wipePersonalData(SysUser user) {
        Long userId = user.getId();

        // ① 健康五表（全物理删——用户自助数据，删除即消失）
        metricMapper.delete(new LambdaQueryWrapper<HealthMetric>().eq(HealthMetric::getUserId, userId));
        metricDefMapper.delete(new LambdaQueryWrapper<MetricDef>().eq(MetricDef::getOwnerUserId, userId));
        kvMapper.delete(new LambdaQueryWrapper<HealthKv>().eq(HealthKv::getUserId, userId));
        portraitMapper.delete(new LambdaQueryWrapper<UserPortrait>().eq(UserPortrait::getUserId, userId));
        factMapper.delete(new LambdaQueryWrapper<com.healthscience.rag.entity.ChatHealthFact>()
                .eq(com.healthscience.rag.entity.ChatHealthFact::getUserId, userId));

        // ② 个人库（scope=personal）+ 文档解惑文件（scope=file）资产
        List<KbDocument> ownedDocs = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getOwnerUserId, userId)
                .in(KbDocument::getScope, KbDocument.SCOPE_PERSONAL, "file"));
        for (KbDocument doc : ownedDocs) {
            chunkMapper.nullEmbeddingByDocument(doc.getId());
            chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, doc.getId()));
            documentMapper.deleteById(doc.getId());
            deleteStoredFile(userId, doc.getFilePath(), doc.getScope());
        }

        // ③ 文档解惑：会话-文件绑定 + file 会话与其消息
        List<ChatSession> fileSessions = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId).eq(ChatSession::getKind, "file"));
        for (ChatSession s : fileSessions) {
            qaDocMapper.delete(new LambdaQueryWrapper<com.healthscience.rag.entity.QaSessionDocument>()
                    .eq(com.healthscience.rag.entity.QaSessionDocument::getSessionId, s.getId()));
            messageMapper.deleteBySessionId(s.getId());
            sessionMapper.deleteById(s.getId());
        }

        // ④ 普通会话/消息保留（审计），用户名置「已注销用户」（脱敏）
        user.setUsername("已注销用户_" + userId);
        user.setNickname(null);
        user.setEmail(null);
        user.setAvatar(null);
        user.setTokenInvalidBefore(LocalDateTime.now());
        userMapper.updateById(user);   // 逻辑删由 @TableLogic 的 deleteById 触发？不——此处先改字段
        userMapper.deleteById(userId); // 逻辑删：deleted=true（审计保留行）

        log.info("[Account] 用户注销完成 userId={}：健康五表物理删，个人库 {} 篇清理，file 会话 {} 个删除",
                userId, ownedDocs.size(), fileSessions.size());
    }

    /** 落盘文件清理（个人库 user-kb/ 与文档解惑 kb-qa/；失败不阻断——磁盘由运维兜底） */
    private void deleteStoredFile(Long userId, String storedName, String scope) {
        if (storedName == null || storedName.isBlank()) {
            return;
        }
        try {
            Path path = "file".equals(scope)
                    ? Path.of(uploadDir, "kb-qa", String.valueOf(userId), storedName)
                    : Path.of(uploadDir, "user-kb", String.valueOf(userId), storedName);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("[Account] 落盘清理失败 userId={} file={}: {}", userId, storedName, e.getMessage());
        }
    }
}
