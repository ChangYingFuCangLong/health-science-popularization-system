package com.healthscience.rag.controller;

import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.entity.MetricDef;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 指标目录接口（M16 v4.0，契约 10 号，#60–#62）。
 */
@Tag(name = "指标目录 /api/health")
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class MetricCatalogController {

    private final MetricCatalogService catalogService;

    /** #60 目录：标准 19 项 + 我的自定义（前端按 category 分组渲染） */
    @GetMapping("/metrics/catalog")
    @Operation(summary = "指标目录")
    public R<List<MetricDef>> catalog() {
        return R.ok(catalogService.catalog(UserContext.requireId()));
    }

    /** #61 创建自定义指标（≤10/人 2006；min<max 400） */
    @PostMapping("/metric-defs")
    @Operation(summary = "创建自定义指标")
    public R<MetricDef> create(@org.springframework.validation.annotation.Validated @RequestBody CreateRequest body) {
        return R.ok(catalogService.createCustom(UserContext.requireId(),
                body.label(), body.unit(), body.min(), body.max(),
                Boolean.TRUE.equals(body.hasValue2())));
    }

    /** #62 删除自定义（逻辑删，历史记录只读保留；标准指标 400） */
    @DeleteMapping("/metric-defs/{id}")
    @Operation(summary = "删除自定义指标")
    public R<Void> delete(@PathVariable Long id) {
        catalogService.deleteCustom(UserContext.requireId(), id);
        return R.ok();
    }

    /** #61 入参 */
    public record CreateRequest(
            @NotBlank(message = "名称不能为空")
            @Size(max = 30, message = "名称不超过 30 字")
            String label,
            @Size(max = 20, message = "单位不超过 20 字")
            String unit,
            BigDecimal min,
            BigDecimal max,
            Boolean hasValue2
    ) {
    }
}
