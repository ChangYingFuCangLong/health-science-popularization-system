package com.healthscience.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.entity.HealthKv;
import com.healthscience.rag.mapper.HealthKvMapper;
import com.healthscience.rag.service.impl.MetricCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 健康备忘 KV 接口（M16 v4.0，契约 11 号，#65–#68）。
 *
 * <p>自由文本健康信息（一人一 key 覆盖，≤50 条/人）；第四层注入来源。
 */
@Tag(name = "健康备忘 /api/health/kv")
@RestController
@RequestMapping("/api/health/kv")
@RequiredArgsConstructor
public class HealthKvController {

    /** 备忘条数上限（11 号 §配额，超限 2006） */
    private static final int MAX_KV_PER_USER = 50;

    private final HealthKvMapper kvMapper;
    private final MetricCatalogService catalogService;

    /** #65 我的备忘列表 */
    @GetMapping
    @Operation(summary = "备忘列表")
    public R<List<HealthKv>> list() {
        Long userId = UserContext.requireId();
        return R.ok(kvMapper.selectList(new LambdaQueryWrapper<HealthKv>()
                .eq(HealthKv::getUserId, userId)
                .orderByDesc(HealthKv::getUpdateTime)));
    }

    /**
     * #66 新增备忘（11 号 §2.2：自由 itemKey/itemValue，**可重复**——如"用药"可多条；
     * ≤50 条/人 2006；不再做目录校验：指标=数值可趋势走目录，KV=信息型文本自由填写）
     */
    @PostMapping
    @Operation(summary = "新增备忘")
    public R<java.util.Map<String, Object>> save(
            @org.springframework.validation.annotation.Validated @RequestBody SaveRequest body) {
        Long userId = UserContext.requireId();
        Long count = kvMapper.selectCount(new LambdaQueryWrapper<HealthKv>()
                .eq(HealthKv::getUserId, userId));
        if (count != null && count >= MAX_KV_PER_USER) {
            throw new BizException(ErrorCode.KB_QUOTA_EXCEEDED,
                    "健康备忘已达上限 " + MAX_KV_PER_USER + " 条（可删除旧条目后再添加）");
        }
        HealthKv kv = new HealthKv();
        kv.setUserId(userId);
        kv.setItemKey(body.itemKey());
        // label 列已随现库退役
        kv.setItemKey(body.itemKey());
        kv.setItemValue(body.itemValue());
        kv.setSource("report".equals(body.source()) ? "report" : "manual");
        LocalDateTime now = LocalDateTime.now();
        kv.setCreateTime(now);
        kv.setUpdateTime(now);
        kvMapper.insert(kv);
        return R.ok(java.util.Map.of("id", kv.getId()));
    }

    /** #67 修改（非 key 字段） */
    @PutMapping("/{id}")
    @Operation(summary = "修改备忘")
    public R<Void> update(@PathVariable Long id, @RequestBody SaveRequest body) {
        HealthKv kv = requireOwned(id);
        applyBody(kv, body);
        kv.setUpdateTime(LocalDateTime.now());
        kvMapper.updateById(kv);
        return R.ok();
    }

    /** #68 删除（物理删） */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除备忘")
    public R<Void> delete(@PathVariable Long id) {
        requireOwned(id);
        kvMapper.deleteById(id);
        return R.ok();
    }

    private HealthKv requireOwned(Long id) {
        HealthKv kv = kvMapper.selectById(id);
        if (kv == null || !UserContext.requireId().equals(kv.getUserId())) {
            throw BizException.notFound("备忘不存在");
        }
        return kv;
    }

    private void applyBody(HealthKv kv, SaveRequest body) {
        if (body.itemKey() != null) {
            kv.setItemKey(body.itemKey());
            // label 列已随现库退役
        kv.setItemKey(body.itemKey());
        }
        if (body.itemValue() != null) {
            kv.setItemValue(body.itemValue());
        }
    }

    /** #66 入参（11 号 §2.2：itemKey ≤50 / itemValue ≤500，可重复） */
    public record SaveRequest(
            @NotBlank(message = "名称不能为空")
            @Size(max = 50, message = "名称不超过 50 字")
            String itemKey,
            @NotBlank(message = "内容不能为空")
            @Size(max = 500, message = "内容不超过 500 字")
            String itemValue,
            /** manual / report（#64 确认入库走 report） */
            String source
    ) {
    }
}
