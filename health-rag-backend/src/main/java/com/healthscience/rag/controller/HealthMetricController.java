package com.healthscience.rag.controller;

import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.model.dto.HealthDTO;
import com.healthscience.rag.service.HealthMetricService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康档案接口（04-裁决 §4.1，接口文档 02 §十）。
 *
 * <p>登录用户可用；操作目标一律取 {@code UserContext.requireId()}，
 * 不接受路径或参数指定他人 ID。前端「健康中心」在 localStorage 降级与本组接口间自动切换。
 */
@Tag(name = "健康档案 /api/health")
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthMetricController {

    private final HealthMetricService healthMetricService;

    /** §10.1 查询（type 可选，days 默认 90） */
    @GetMapping("/metrics")
    public R<HealthDTO.MetricListVO> list(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "90") int days) {
        Long userId = UserContext.requireId();
        return R.ok(new HealthDTO.MetricListVO(healthMetricService.list(userId, type, days)));
    }

    /** §10.2 新增，返回新记录 ID */
    @PostMapping("/metrics")
    public R<Map<String, Object>> add(@Valid @RequestBody HealthDTO.AddRequest request) {
        Long userId = UserContext.requireId();
        return R.ok(Map.of("id", healthMetricService.add(userId, request)));
    }

    /** §10.3 删除（物理删除，仅本人记录） */
    @DeleteMapping("/metrics/{id}")
    public R<Void> delete(@PathVariable Long id) {
        healthMetricService.delete(UserContext.requireId(), id);
        return R.ok();
    }
}
