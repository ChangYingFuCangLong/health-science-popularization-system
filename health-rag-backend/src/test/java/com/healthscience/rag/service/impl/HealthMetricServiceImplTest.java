package com.healthscience.rag.service.impl;

import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.entity.HealthMetric;
import com.healthscience.rag.entity.MetricDef;
import com.healthscience.rag.mapper.HealthMetricMapper;
import com.healthscience.rag.model.dto.HealthDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 健康档案校验规则单测（M9/B-6）。
 *
 * <p>区间与语义约束（02-接口文档 §10.2）：体重 20~300 ｜ 收缩压 60~260 ｜
 * 舒张压 40~200（仅 bp）｜ 睡眠 0~24 ｜ 步数 0~100000；越界 3001，时间非法 400。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HealthMetricServiceImplTest {

    /** 测试目录：复刻现库 19 项中用到的 5 项（含 bp 双值） */
    private static final java.util.Map<String, MetricDef> CATALOG = java.util.Map.of(
            "weight", def("weight", "体重", "kg", 20, 300, false),
            "bp", def("bp", "血压", "mmHg", 40, 260, true),
            "sleep", def("sleep", "睡眠时长", "h", 0, 24, false),
            "steps", def("steps", "步数", "步", 0, 100000, false),
            "waist", def("waist", "腰围", "cm", 30, 200, false));

    private static MetricDef def(String key, String label, String unit, double min, double max, boolean has2) {
        MetricDef d = new MetricDef();
        d.setMetricKey(key);
        d.setLabel(label);
        d.setUnit(unit);
        d.setValueMin(new java.math.BigDecimal(min));
        d.setValueMax(new java.math.BigDecimal(max));
        d.setHasValue2(has2);
        d.setCategory("basic");
        return d;
    }

    private MetricCatalogService catalogService;

    private static final Long USER_ID = 1L;

    @Mock
    private HealthMetricMapper metricMapper;

    private HealthMetricServiceImpl service;

    @BeforeEach
    void setUp() {
        // B-29：校验查目录——目录 Mock 走真实 MetricCatalogService(空 mapper)+测试自建目录
        com.healthscience.rag.mapper.MetricDefMapper mdMapper =
                org.mockito.Mockito.mock(com.healthscience.rag.mapper.MetricDefMapper.class);
        org.mockito.Mockito.when(mdMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetricDef> w = inv.getArgument(0);
                    return null;   // selectByKey 走不到（catalog.resolve 用 selectOne）；走 selectCatalog 也不需要
                });
        catalogService = new MetricCatalogService(mdMapper) {
            @Override
            public MetricDef resolve(Long userId, String key) {
                return CATALOG.get(key);
            }
        };
        service = new HealthMetricServiceImpl(metricMapper, catalogService);
        when(metricMapper.insert(any(HealthMetric.class))).thenAnswer(inv -> {
            ((HealthMetric) inv.getArgument(0)).setId(101L);
            return 1;
        });
    }

    private HealthDTO.AddRequest request(String type, String value, String value2) {
        return new HealthDTO.AddRequest(type,
                value == null ? null : new BigDecimal(value),
                value2 == null ? null : new BigDecimal(value2),
                null, null);
    }

    @Test
    @DisplayName("血压记录合法：插入成功并返回新 ID")
    void addValidBp() {
        Long id = service.add(USER_ID, request("bp", "122", "81"));

        assertThat(id).isEqualTo(101L);
        verify(metricMapper).insert(any(HealthMetric.class));
    }

    @Test
    @DisplayName("血压缺舒张压 → 3001")
    void addBpWithoutValue2Rejected() {
        assertThatThrownBy(() -> service.add(USER_ID, request("bp", "122", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(3001);
    }

    @Test
    @DisplayName("非血压类型携带 value2 → 3001")
    void addNonBpWithValue2Rejected() {
        assertThatThrownBy(() -> service.add(USER_ID, request("weight", "65.5", "81")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(3001);
    }

    @Test
    @DisplayName("体重低于下限 → 3001")
    void addWeightBelowRangeRejected() {
        assertThatThrownBy(() -> service.add(USER_ID, request("weight", "19", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(3001);
    }

    @Test
    @DisplayName("步数高于上限 → 3001")
    void addStepsAboveRangeRejected() {
        assertThatThrownBy(() -> service.add(USER_ID, request("steps", "100001", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(3001);
    }

    @Test
    @DisplayName("睡眠边界值 0 与 24 合法")
    void addSleepBoundaryValuesAccepted() {
        service.add(USER_ID, request("sleep", "0", null));
        service.add(USER_ID, request("sleep", "24", null));

        verify(metricMapper, org.mockito.Mockito.times(2)).insert(any(HealthMetric.class));
    }

    @Test
    @DisplayName("recordedAt 晚于当前时间 1 分钟以上 → 400")
    void addFutureRecordedAtRejected() {
        HealthDTO.AddRequest r = new HealthDTO.AddRequest("weight", new BigDecimal("65"),
                null, LocalDateTime.now().plusHours(1), null);

        assertThatThrownBy(() -> service.add(USER_ID, r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("recordedAt 为未来日期的整点（00:00:00）也必须 400——P3-5 回归")
    void addFutureMidnightRejected() {
        HealthDTO.AddRequest tomorrowMidnight = new HealthDTO.AddRequest("weight", new BigDecimal("65"),
                null, java.time.LocalDate.now().plusDays(1).atStartOfDay(), null);

        assertThatThrownBy(() -> service.add(USER_ID, tomorrowMidnight))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("recordedAt 早于 2000-01-01 → 400")
    void addTooOldRecordedAtRejected() {
        HealthDTO.AddRequest r = new HealthDTO.AddRequest("weight", new BigDecimal("65"),
                null, LocalDateTime.of(1999, 12, 31, 23, 0), null);

        assertThatThrownBy(() -> service.add(USER_ID, r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("删除：他人记录 → 403；本人记录 → 物理 DELETE")
    void deleteChecksOwnership() {
        HealthMetric foreign = new HealthMetric();
        foreign.setId(9L);
        foreign.setUserId(99L);
        when(metricMapper.selectById(9L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.delete(USER_ID, 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权限");

        foreign.setUserId(USER_ID);
        service.delete(USER_ID, 9L);
        verify(metricMapper).deleteById(9L);   // 无逻辑删除：真实 DELETE
    }

    @Test
    @DisplayName("删除：记录不存在 → 404")
    void deleteMissingRecord() {
        when(metricMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(USER_ID, 404L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(404);
        verify(metricMapper, never()).deleteById(any(java.io.Serializable.class));
    }
}
