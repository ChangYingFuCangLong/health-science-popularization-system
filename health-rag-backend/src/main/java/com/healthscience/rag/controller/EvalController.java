package com.healthscience.rag.controller;

import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.common.result.R;
import com.healthscience.rag.model.dto.AdminDTO;
import com.healthscience.rag.service.EvalService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 评测与实验接口，对应接口文档 §八。
 *
 * <p><b>权限</b>：{@code /api/admin/**} 由 Security 链统一 {@code hasRole(ADMIN)} 强制。
 * 本组接口是论文「实验与评估」章节的数据出口，B-22 补齐后闭环为
 * 「录题 → 跑批（检索+生成+判分）→ 人工复核 → 汇总」。
 */
@Tag(name = "评测与实验 /api/admin/eval")
@RestController
@RequestMapping("/api/admin/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    /** §7.1 新增评测问题 */
    @PostMapping("/dataset")
    public R<Map<String, Object>> addDataset(@RequestBody EvalItemRequest body) {
        if (body == null || body.question() == null || body.question().isBlank()) {
            throw BizException.badRequest("评测问题不能为空");
        }
        Long id = evalService.addDataset(
                body.question(), body.groundTruth(), body.refChunkIds(),
                body.category(), body.difficulty());
        return R.ok(Map.of("id", id));
    }

    /** §7.2 评测集列表 */
    @GetMapping("/dataset")
    public R<PageResult<AdminDTO.EvalItemVO>> dataset(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(evalService.dataset(category, pageNum, pageSize));
    }

    /**
     * §7.3 执行实验。
     *
     * <p>{@code generate=true} 会逐题生成答案并跑自动判分，耗时随题量线性上升；
     * 只算 HitRate/MRR 时保持默认 {@code false} 即可，几十秒内出结果。
     */
    @PostMapping("/run")
    public R<Map<String, Object>> run(@RequestBody RunRequest body) {
        if (body == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        String batchNo = evalService.runExperiment(
                body.strategies(), body.datasetIds(),
                Boolean.TRUE.equals(body.generate()));
        return R.ok(Map.of("batchNo", batchNo));
    }

    /** §7.4 实验结果查询 */
    @GetMapping("/results")
    public R<PageResult<AdminDTO.EvalResultVO>> results(
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(evalService.results(batchNo, strategy, pageNum, pageSize));
    }

    /**
     * 🟡 B-22 人工复核：覆盖分数并置 {@code score_source='MANUAL'}。
     *
     * <p>两个分数至少给一个；都不给视为无效请求，避免误把某条打成 MANUAL 却没有实际改动。
     */
    @PutMapping("/results/{id}")
    public R<Void> review(@PathVariable Long id, @RequestBody ReviewRequest body) {
        if (body == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        evalService.review(id, body.faithfulness(), body.citationCorrect());
        return R.ok(null);
    }

    /** §7.5 实验指标汇总（不传 batchNo 时取最近批次） */
    @GetMapping("/summary")
    public R<AdminDTO.EvalSummaryVO> summary(@RequestParam(required = false) String batchNo) {
        return R.ok(evalService.summary(batchNo));
    }

    /** §7.1 入参 */
    public record EvalItemRequest(
            String question,
            String groundTruth,
            List<Long> refChunkIds,
            String category,
            Integer difficulty
    ) {
    }

    /** §7.3 入参；{@code generate} 缺省 false */
    public record RunRequest(List<String> strategies, List<Long> datasetIds, Boolean generate) {
    }

    /** B-22 人工复核入参 */
    public record ReviewRequest(BigDecimal faithfulness, Integer citationCorrect) {
    }
}
