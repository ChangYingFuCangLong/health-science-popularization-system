package com.healthscience.rag.controller;

import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.model.dto.UserKbDTO;
import com.healthscience.rag.service.UserKbService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 用户个人知识库接口（M15，契约 07，#44–#52）。
 *
 * <p><b>隔离纪律</b>：所有操作只作用于当前登录用户自己的文档；越权一律 404。
 * <b>隐私</b>：个人库内容对管理端不可见（07 决策 D3）。
 */
@Tag(name = "个人知识库 /api/user/kb")
@RestController
@RequiredArgsConstructor
public class UserKbController {

    private final UserKbService userKbService;

    /** #44 概览与配额（max* 供前端画配额进度条） */
    @GetMapping("/api/user/kb/stats")
    @Operation(summary = "个人库概览与配额")
    public R<UserKbDTO.StatsVO> stats() {
        return R.ok(userKbService.stats(UserContext.requireId()));
    }

    /** #45 文档列表（keyword 匹配标题，status 缺省不过滤） */
    @GetMapping("/api/user/kb/documents")
    @Operation(summary = "个人库文档列表")
    public R<PageResult<UserKbDTO.DocumentVO>> documents(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Long userId = UserContext.requireId();
        return R.ok(userKbService.documents(userId, keyword, status, pageNum, pageSize));
    }

    /** #46 上传并触发处理（限流 5 次/分钟/用户，见 WebMvcConfig） */
    @PostMapping(value = "/api/user/kb/documents/upload",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传个人资料")
    public R<Map<String, Object>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "title", required = false) String title) {
        Long userId = UserContext.requireId();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.KB_FILE_INVALID, "文件读取失败");
        }
        Long id = userKbService.upload(userId, file.getOriginalFilename(), content, title);
        return R.ok(Map.of("id", id, "status", 1));
    }

    /** #47 处理进度（前端 1.5s 轮询，status ∈ {2,3} 停止） */
    @GetMapping("/api/user/kb/documents/{id}/progress")
    @Operation(summary = "个人库处理进度")
    public R<UserKbDTO.ProgressVO> progress(@PathVariable Long id) {
        return R.ok(userKbService.progress(UserContext.requireId(), id));
    }

    /** #48 分块预览（"透明度"卖点：让用户看到自己的文件被切成什么样） */
    @GetMapping("/api/user/kb/documents/{id}/chunks")
    @Operation(summary = "个人库分块预览")
    public R<PageResult<UserKbDTO.ChunkPreviewVO>> chunks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(userKbService.chunks(UserContext.requireId(), id, pageNum, pageSize));
    }

    /** #49 删除（级联 + 落盘文件清理；两步确认由前端承担） */
    @DeleteMapping("/api/user/kb/documents/{id}")
    @Operation(summary = "删除个人文档")
    public R<Void> delete(@PathVariable Long id) {
        userKbService.delete(UserContext.requireId(), id);
        return R.ok();
    }

    /** #50 单文档重新向量化 */
    @PostMapping("/api/user/kb/documents/{id}/rebuild")
    @Operation(summary = "个人文档重新向量化")
    public R<Void> rebuild(@PathVariable Long id) {
        userKbService.rebuild(UserContext.requireId(), id);
        return R.ok();
    }

    /** #51 读取偏好 */
    @GetMapping("/api/user/kb/settings")
    @Operation(summary = "读取个人库偏好")
    public R<UserKbDTO.SettingsVO> settings() {
        return R.ok(userKbService.settings(UserContext.requireId()));
    }

    /** #52 更新偏好 */
    @PutMapping("/api/user/kb/settings")
    @Operation(summary = "更新个人库偏好")
    public R<Void> updateSettings(@org.springframework.web.bind.annotation.RequestBody
                                  UserKbDTO.SettingsUpdateRequest request) {
        userKbService.updateSettings(UserContext.requireId(), request);
        return R.ok();
    }
}
