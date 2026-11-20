package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.entity.MetricDef;
import com.healthscience.rag.mapper.MetricDefMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指标目录服务（B-29，契约 10 号）：目录查询 / 自定义指标 / 校验解析的单一真相。
 *
 * <p>所有指标的取值校验（健康中心录入、画像同步、报告抽取确认）一律查本目录；
 * 防臃肿四原则：自定义 ≤10/人、key 前缀 {@code p_}、与标准目录 key 不冲突。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricCatalogService {

    /** 自定义指标上限（B-29 防臃肿） */
    public static final int MAX_CUSTOM_PER_USER = 10;

    /** 自定义指标 key 前缀（与标准 key 命名空间隔离） */
    public static final String CUSTOM_PREFIX = "p_";

    private final MetricDefMapper metricDefMapper;

    /** #60 目录：标准 19 项（按 category 分组由前端处理）+ 我的自定义 */
    public List<MetricDef> catalog(Long userId) {
        return metricDefMapper.selectCatalog(userId);
    }

    /** #61 创建自定义指标（≤10/人，超限 2006；key 冲突 400；范围必填 min<max） */
    public MetricDef createCustom(Long userId, String label, String unit,
                                  BigDecimal min, BigDecimal max, boolean hasValue2) {
        Long count = metricDefMapper.selectCount(new LambdaQueryWrapper<MetricDef>()
                .eq(MetricDef::getOwnerUserId, userId)
                .eq(MetricDef::getCategory, "custom"));
        if (count != null && count >= MAX_CUSTOM_PER_USER) {
            throw new BizException(ErrorCode.KB_QUOTA_EXCEEDED,
                    "自定义指标已达上限 " + MAX_CUSTOM_PER_USER + " 个");
        }
        if (min == null || max == null || min.compareTo(max) >= 0) {
            throw BizException.badRequest("取值范围必须满足 min < max");
        }
        // key = p_{userId}_{label hash}——稳定、可读、天然不与标准 key 冲突
        String key = CUSTOM_PREFIX + userId + "_" + Integer.toHexString(label.hashCode());
        MetricDef exist = selectByKey(key);
        if (exist != null) {
            throw BizException.badRequest("同名自定义指标已存在");
        }
        MetricDef def = new MetricDef();
        def.setMetricKey(key);
        def.setLabel(label);
        def.setUnit(unit);
        def.setValueMin(min);
        def.setValueMax(max);
        def.setCategory("custom");
        def.setOwnerUserId(userId);
        def.setHasValue2(hasValue2);
        if (hasValue2) {
            def.setValue2Label("第二数值");
        }
        LocalDateTime now = LocalDateTime.now();
        def.setCreateTime(now);
        def.setUpdateTime(now);
        metricDefMapper.insert(def);
        return def;
    }

    /** #62 删除自定义（逻辑删——历史 health_metric 记录只读保留；标准指标 400；他人指标 404） */
    public void deleteCustom(Long userId, Long id) {
        MetricDef def = metricDefMapper.selectById(id);
        if (def == null || def.getDeleted() || !userId.equals(def.getOwnerUserId())) {
            throw BizException.notFound("指标不存在");
        }
        if (!"custom".equals(def.getCategory())) {
            throw BizException.badRequest("标准指标不可删除");
        }
        metricDefMapper.deleteById(id);   // @TableLogic 逻辑删
    }

    /** 按 key 查可用定义（标准或本人自定义）；未找到/已删/他人自定义 → null */
    public MetricDef resolve(Long userId, String key) {
        MetricDef def = selectByKey(key);
        if (def == null) {
            return null;
        }
        if (def.getOwnerUserId() != null && !userId.equals(def.getOwnerUserId())) {
            return null;
        }
        return def;
    }

    /** 范围校验：越界抛 3001（含语义 message） */
    public void validateRange(MetricDef def, BigDecimal value) {
        if (def == null || value == null) {
            return;
        }
        if (def.getValueMin() != null && value.compareTo(def.getValueMin()) < 0
                || def.getValueMax() != null && value.compareTo(def.getValueMax()) > 0) {
            throw new BizException(ErrorCode.HEALTH_METRIC_OUT_OF_RANGE,
                    def.getLabel() + "须在 " + def.getValueMin().stripTrailingZeros().toPlainString()
                            + " ~ " + def.getValueMax().stripTrailingZeros().toPlainString()
                            + (def.getUnit() == null ? "" : " " + def.getUnit()) + " 之间");
        }
    }

    private MetricDef selectByKey(String key) {
        return metricDefMapper.selectOne(new LambdaQueryWrapper<MetricDef>()
                .eq(MetricDef::getMetricKey, key).last("LIMIT 1"));
    }
}
