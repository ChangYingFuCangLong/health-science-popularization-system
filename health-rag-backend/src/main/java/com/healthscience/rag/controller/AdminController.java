package com.healthscience.rag.controller;

import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.entity.MessageFeedback;
import com.healthscience.rag.model.dto.AdminDTO;
import com.healthscience.rag.service.AdminService;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端接口，对应接口文档 §6。
 *
 * <p><b>权限</b>：{@code /api/admin/**} 由 Security 链统一 {@code hasRole(ADMIN)} 强制
 * （见 SecurityConfig），本类不再重复校验。
 */
@Tag(name = "管理端 /api/admin")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final AdminService adminService;
    private final com.healthscience.rag.service.AccountService accountService;

    /** §6.2 文档列表 */
    @GetMapping("/documents")
    public R<PageResult<KbDocument>> documents(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(adminService.documents(status, category, keyword, pageNum, pageSize));
    }

    /** §6.2 上传文档（M12/B-19）：受理后异步解析分块向量化，进度走 /progress */
    @org.springframework.web.bind.annotation.PostMapping(value = "/documents/upload",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source) {
        try {
            Long id = adminService.uploadDocument(file.getOriginalFilename(), file.getBytes(),
                    title, category, source, UserContext.requireId());
            return R.ok(Map.of("documentId", id, "status", 1, "message", "已受理，正在解析"));
        } catch (java.io.IOException e) {
            throw BizException.badRequest("文件读取失败");
        }
    }

    /** §6.3 处理进度（M12/B-20）：前端 2s 轮询，status=2 停止并刷新列表 */
    @org.springframework.web.bind.annotation.GetMapping("/documents/{id}/progress")
    public R<AdminDTO.DocumentProgressVO> documentProgress(@PathVariable Long id) {
        return R.ok(adminService.documentProgress(id));
    }

    /** §6.5 查看文档分块 */
    @GetMapping("/documents/{id}/chunks")
    public R<List<AdminDTO.ChunkVO>> documentChunks(@PathVariable Long id) {
        return R.ok(adminService.documentChunks(id));
    }

    /** §6.7 向量索引元信息 */
    @GetMapping("/vectors/meta")
    public R<AdminDTO.VectorMetaVO> vectorMeta() {
        return R.ok(adminService.vectorMeta());
    }

    /** §6.8 数据看板 */
    @GetMapping("/stats/overview")
    public R<AdminDTO.OverviewVO> overview() {
        return R.ok(adminService.overview());
    }

    /** §6.9 问答日志 */
    @GetMapping("/chat-logs")
    public R<PageResult<AdminDTO.ChatLogVO>> chatLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer isRejected,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(adminService.chatLogs(userId, isRejected, startDate, endDate, pageNum, pageSize));
    }

    /** §6.10 反馈列表 */
    @GetMapping("/feedbacks")
    public R<PageResult<AdminDTO.FeedbackVO>> feedbacks(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(adminService.feedbacks(status, pageNum, pageSize));
    }

    /** §6.3 删除文档（连同分块与向量） */
    @org.springframework.web.bind.annotation.DeleteMapping("/documents/{id}")
    public R<Void> deleteDocument(@PathVariable Long id) {

        adminService.deleteDocument(id);
        return R.ok();
    }

    /** §6.4 重建单个文档索引（异步） */
    @org.springframework.web.bind.annotation.PostMapping("/documents/{id}/rebuild")
    public R<Map<String, Object>> rebuildDocument(@PathVariable Long id) {
        if (adminService.isRebuilding()) {
            throw BizException.badRequest("索引正在重建中，请稍后再试");
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                adminService.rebuildDocument(id);
            } catch (RuntimeException e) {
                log.error("重建文档索引失败, documentId={}", id, e);
            }
        });
        return R.ok(Map.of("accepted", true, "message", "重建任务已提交"));
    }

    /** §6.6 全量重建向量索引（异步） */
    @org.springframework.web.bind.annotation.PostMapping("/vectors/rebuild")
    public R<Map<String, Object>> rebuildVectors() {
        if (adminService.isRebuilding()) {
            throw BizException.badRequest("索引正在重建中，请稍后再试");
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                adminService.rebuildAllVectors();
            } catch (RuntimeException e) {
                log.error("全量重建向量索引失败", e);
            }
        });
        return R.ok(Map.of("accepted", true, "message", "重建任务已提交"));
    }

    /** §6.10 检索过程回放（M14/B-23）：两路召回、RRF、rerank、置信度与 decision */
    @org.springframework.web.bind.annotation.GetMapping("/chat-logs/{messageId}/retrieval-trace")
    public R<Map<String, Object>> retrievalTrace(@PathVariable Long messageId) {
        return R.ok(adminService.retrievalTrace(messageId));
    }

    /** §6.10 处理反馈（status 实际生效：0 待处理 / 1 已处理，缺省视为已处理） */
    @org.springframework.web.bind.annotation.PutMapping("/feedbacks/{id}")
    public R<Void> handleFeedback(@PathVariable Long id,
                                  @RequestBody(required = false) StatusRequest body) {

        Integer status = body == null || body.status() == null
                ? MessageFeedback.STATUS_HANDLED : body.status();
        adminService.markFeedbackHandled(id, status, UserContext.requireId());
        return R.ok();
    }

    /** §6.11 启用 / 禁用用户 */
    @org.springframework.web.bind.annotation.PutMapping("/users/{id}/status")
    public R<Void> updateUserStatus(@PathVariable Long id, @RequestBody StatusRequest body) {
        if (body == null || body.status() == null) {
            throw BizException.badRequest("状态不能为空");
        }
        adminService.updateUserStatus(id, body.status(), UserContext.requireId());
        return R.ok();
    }

    /** §七 #44 删除用户（B-25）：不存在 404；删自己/删 ADMIN 400；个人资产全量清理+踢下线 */
    @org.springframework.web.bind.annotation.DeleteMapping("/users/{id}")
    public R<Void> deleteUser(@PathVariable Long id) {
        accountService.deleteByAdmin(id, UserContext.requireId());
        return R.ok();
    }

    /** 状态变更入参 */
    public record StatusRequest(Integer status) {
    }

    /** §6.11 用户列表（F1：支持 keyword 模糊搜索用户名/昵称） */
    @GetMapping("/users")
    public R<PageResult<AdminDTO.UserVO>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(adminService.users(pageNum, pageSize, keyword));
    }
}
