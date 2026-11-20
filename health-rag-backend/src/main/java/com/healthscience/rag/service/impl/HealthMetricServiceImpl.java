package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.entity.HealthMetric;
import com.healthscience.rag.mapper.HealthMetricMapper;
import com.healthscience.rag.model.dto.HealthDTO;
import com.healthscience.rag.service.HealthMetricService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 健康档案实现。
 *
 * <p><b>越界为何返回 3001 而非 400</b>：参数在结构上合法（数字、类型枚举正确），
 * 语义上越出健康合理区间 —— 前端需要区分「格式错误（表单红框）」与
 * 「数值不合理（业务提示）」两种反馈，契约 02 §10.2 因此单独定义 3001。
 */
@Service
@RequiredArgsConstructor
public class HealthMetricServiceImpl implements HealthMetricService {

    /** recordedAt 允许的最早时间：早于它视为录入错误（400） */
    private static final LocalDateTime EARLIEST_ALLOWED = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final HealthMetricMapper metricMapper;
    private final MetricCatalogService catalogService;

    @Override
    public List<HealthDTO.MetricVO> list(Long userId, String type, int days) {
        int windowDays = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);
        LambdaQueryWrapper<HealthMetric> q = new LambdaQueryWrapper<HealthMetric>()
                .eq(HealthMetric::getUserId, userId)
                .ge(HealthMetric::getRecordedAt, since)
                .orderByAsc(HealthMetric::getRecordedAt);
        if (StringUtils.hasText(type)) {
            q.eq(HealthMetric::getType, type.trim());
        }
        return metricMapper.selectList(q).stream()
                .map(m -> new HealthDTO.MetricVO(m.getId(), m.getType(), m.getValue(), m.getValue2(),
                        m.getRecordedAt(), m.getNote(), m.getCreateTime()))
                .toList();
    }

    @Override
    public Long add(Long userId, HealthDTO.AddRequest request) {
        String type = request.type().trim();
        validateAgainstCatalog(userId, type, request.value(), request.value2());
        LocalDateTime recordedAt = validateRecordedAt(request.recordedAt());

        HealthMetric metric = new HealthMetric();
        metric.setUserId(userId);
        metric.setType(type);
        metric.setValue(request.value());
        metric.setValue2(request.value2());
        metric.setRecordedAt(recordedAt);
        metric.setNote(request.note());
        LocalDateTime now = LocalDateTime.now();
        metric.setCreateTime(now);
        metric.setUpdateTime(now);
        metricMapper.insert(metric);
        return metric.getId();
    }

    @Override
    public void delete(Long userId, Long id) {
        HealthMetric metric = metricMapper.selectById(id);
        if (metric == null) {
            throw BizException.notFound("记录不存在");
        }
        if (!userId.equals(metric.getUserId())) {
            throw BizException.forbidden("记录不存在或无权限");
        }
        metricMapper.deleteById(id);   // 无 @TableLogic：真实 DELETE，即录即删
    }

    /** 取值区间（02 §10.2）：越界 3001；value2 只允许出现在 bp */
    /**
     * B-29：取值校验查指标目录（单一真相）——type 放开为目录引用（含自定义 p_*）。
     * 旧 4 键（weight/bp/sleep/steps）在目录中，行为不变；范围越界仍 3001。
     */
    private void validateAgainstCatalog(Long userId, String type, BigDecimal value, BigDecimal value2) {
        com.healthscience.rag.entity.MetricDef def = catalogService.resolve(userId, type);
        if (def == null) {
            throw BizException.badRequest("未知的指标类型: " + type + "（可在指标目录中查看可选项）");
        }
        catalogService.validateRange(def, value);
        if (value2 != null && Boolean.TRUE.equals(def.getHasValue2())) {
            catalogService.validateRange(def, value2);
        }
        if (Boolean.TRUE.equals(def.getHasValue2()) && value2 == null) {
            throw outOfRange(def.getLabel() + " 需同时填写第二数值（" + def.getValue2Label() + "）");
        }
        if (!Boolean.TRUE.equals(def.getHasValue2()) && value2 != null) {
            throw outOfRange("该指标不支持第二数值");
        }
    }

    private static BizException outOfRange(String message) {
        return new BizException(com.healthscience.rag.common.result.ErrorCode.HEALTH_METRIC_OUT_OF_RANGE, message);
    }

    /** recordedAt 缺省取当前时间；晚于当前 1 分钟以上或早于 2000-01-01 → 400 */
    private LocalDateTime validateRecordedAt(LocalDateTime recordedAt) {
        if (recordedAt == null) {
            return LocalDateTime.now();
        }
        if (recordedAt.isAfter(LocalDateTime.now().plusMinutes(1))) {
            // 仅「今天 00:00:00」放行（日期粒度录入兜底）；未来的整点一律拒绝
            // （测试报告 P3-5：原实现豁免任意整点，2099-01-01 00:00:00 可绕过）
            boolean todayMidnight = recordedAt.toLocalTime().equals(LocalTime.MIN)
                    && recordedAt.toLocalDate().equals(java.time.LocalDate.now());
            if (!todayMidnight) {
                throw BizException.badRequest("记录时间不能晚于当前时间");
            }
        }
        if (recordedAt.isBefore(EARLIEST_ALLOWED)) {
            throw BizException.badRequest("记录时间不能早于 2000-01-01");
        }
        return recordedAt;
    }
}
