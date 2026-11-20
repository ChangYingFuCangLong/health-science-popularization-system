package com.healthscience.rag.service;

import com.healthscience.rag.model.dto.HealthDTO;

import java.util.List;

/**
 * 健康档案（04-裁决 §4.1，前端「健康中心」P0），对应接口文档 02 §十。
 *
 * <p>数据为用户个人自助数据：只增删改查本人记录，无任何跨用户分析；
 * 删除为物理删除（无回收站）。
 */
public interface HealthMetricService {

    /** 可查询的最大天数窗口 */
    int MAX_DAYS = 365;

    /** §10.1 查询：本人记录，可选按 type 过滤，最近 days 天，按 recordedAt 升序，非分页 */
    List<HealthDTO.MetricVO> list(Long userId, String type, int days);

    /** §10.2 新增：返回新记录 ID */
    Long add(Long userId, HealthDTO.AddRequest request);

    /** §10.3 删除：物理删除，仅本人记录 */
    void delete(Long userId, Long id);
}
