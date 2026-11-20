package com.healthscience.rag.controller;

import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.service.impl.HealthReportExtractService;
import com.healthscience.rag.service.UserKbService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 体检报告识读接口（M16 v4.0，契约 11 号，#63/#64）。
 *
 * <p><b>隐私</b>：报告文本将发送 DashScope 做结构化识别（知情同意文案由前端承载）；
 * 草稿只在内存（TTL 10min），取消=零痕迹；管理端不可见。
 */
@Tag(name = "报告识读 /api/health/report")
@RestController
@RequestMapping("/api/health/report")
@RequiredArgsConstructor
public class HealthReportController {

    private final HealthReportExtractService extractService;
    private final UserKbService userKbService;

    /**
     * #63 抽取：multipart file + alsoToKb?（true 时同文件并入个人库流水线，
     * 失败不阻塞抽取——响应 kbDocumentId=null 并提示，两产物解耦）。
     */
    @PostMapping(value = "/extract", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传报告抽取候选草稿")
    public R<Map<String, Object>> extract(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "alsoToKb", required = false, defaultValue = "false") boolean alsoToKb) {
        Long userId = UserContext.requireId();
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.KB_FILE_INVALID, "文件不能为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.KB_FILE_INVALID, "文件读取失败");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";

        // alsoToKb：同文件入个人库（失败不阻塞——两产物解耦，11 号）
        Long kbDocumentId = null;
        if (alsoToKb) {
            try {
                kbDocumentId = userKbService.upload(userId, name, bytes, null);
            } catch (BizException e) {
                // 配额满等：抽取继续，kbDocumentId 保持 null
            }
        }
        return R.ok(extractService.extract(userId, bytes, name, ext, kbDocumentId));
    }

    /** #64 确认入库：用户编辑后的候选提交（数值入指标 source=report，文本入备忘；越界跳过计 skipped） */
    @PostMapping("/commit")
    @Operation(summary = "确认抽取结果入库")
    public R<Map<String, Object>> commit(@RequestBody CommitRequest body) {
        Long userId = UserContext.requireId();
        if (body == null || body.draftId() == null || body.draftId().isBlank()) {
            throw BizException.badRequest("draftId 不能为空");
        }
        return R.ok(extractService.commit(userId, body.draftId(),
                body.reportDate(), body.items()));
    }

    /** #64 请求体（items 结构与 #63 响应一致，可增删改） */
    public record CommitRequest(
            String draftId,
            String reportDate,
            List<HealthReportExtractService.DraftItem> items
    ) {
    }
}
