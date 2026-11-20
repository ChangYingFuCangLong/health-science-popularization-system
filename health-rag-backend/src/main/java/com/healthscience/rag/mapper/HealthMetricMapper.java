package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.HealthMetric;
import org.apache.ibatis.annotations.Param;

/**
 * 健康指标 Mapper。
 *
 * <p>查询走 {@code idx_health_metric_user (user_id, type, recorded_at)} 复合索引；
 * 单用户 90 天窗口数据量远小于千行，无需分页（04-裁决 §4.1）。
 */
public interface HealthMetricMapper extends BaseMapper<HealthMetric> {

    /** 每类指标取最新一条（健康对话注入层②，08 §2.3 实时合并） */
    @org.apache.ibatis.annotations.Select("""
            SELECT DISTINCT ON (type) type, value, value2, recorded_at
              FROM health_metric
             WHERE user_id = #{userId} AND deleted = FALSE
             ORDER BY type, recorded_at DESC
            """)
    java.util.List<java.util.Map<String, Object>> selectLatestPerType(@Param("userId") Long userId);
}
