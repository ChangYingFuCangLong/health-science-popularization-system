package com.healthscience.rag.model.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 健康档案传输对象，字段与接口文档 02 §十 严格对应。
 */
public final class HealthDTO {

    private HealthDTO() {
    }

    /** §10.2 新增记录请求 */
    public record AddRequest(
            @NotBlank(message = "类型不能为空")
            @Pattern(regexp = "weight|bp|sleep|steps",
                    message = "类型只能是 weight / bp / sleep / steps")
            String type,

            @NotNull(message = "数值不能为空")
            BigDecimal value,

            /** 仅 bp 必填（舒张压），其余类型必须为 null（服务层校验，越界 3001） */
            @DecimalMin(value = "0", message = "数值不合法")
            BigDecimal value2,

            /** 可选；缺省取服务器当前时间。晚于当前 1 分钟以上或早于 2000-01-01 → 400 */
            LocalDateTime recordedAt,

            @Size(max = 200, message = "备注不超过 200 字")
            String note
    ) {
    }

    /** §10.1 记录项 */
    public record MetricVO(
            Long id,
            String type,
            BigDecimal value,
            BigDecimal value2,
            LocalDateTime recordedAt,
            String note,
            LocalDateTime createTime
    ) {
    }

    /** §10.1 响应体：data.records[]，按 recordedAt 升序，非分页 */
    public record MetricListVO(List<MetricVO> records) {
    }
}
