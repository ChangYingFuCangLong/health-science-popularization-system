package com.healthscience.rag.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康事实规则抽取器单测（B-28 二次完善，任务卡要求 ≥10 用例）。
 */
class HealthFactExtractorTest {

    private static List<String> keys(String text) {
        return HealthFactExtractor.extract(text).stream()
                .map(HealthFactExtractor.ExtractedFact::metricKey)
                .toList();
    }

    @Test
    @DisplayName("血压双值：收缩压+舒张压同时抽取")
    void bloodPressureBothValues() {
        var facts = HealthFactExtractor.extract("我今天量的血压是145/95，有点高");
        assertThat(facts).extracting(HealthFactExtractor.ExtractedFact::metricKey)
                .containsExactly("systolic", "diastolic");
        assertThat(facts.get(0).value()).isEqualTo(new BigDecimal("145"));
        assertThat(facts.get(1).value()).isEqualTo(new BigDecimal("95"));
    }

    @Test
    @DisplayName("血压横杠分隔：145-95 同样抽取双值")
    void bloodPressureDash() {
        assertThat(keys("血压 130-85")).containsExactly("systolic", "diastolic");
    }

    @ParameterizedTest(name = "[{0}] 抽取收缩压")
    @ValueSource(strings = {"收缩压145", "高压是140", "我的收缩压为 150"})
    void systolicVariants(String text) {
        assertThat(keys(text)).contains("systolic");
    }

    @ParameterizedTest(name = "[{0}] 抽取舒张压")
    @ValueSource(strings = {"舒张压95", "低压是90"})
    void diastolicVariants(String text) {
        assertThat(keys(text)).contains("diastolic");
    }

    @Test
    @DisplayName("体重带单位与小数：70.5kg")
    void weightWithUnit() {
        var facts = HealthFactExtractor.extract("最近体重70.5公斤");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).metricKey()).isEqualTo("weight");
        assertThat(facts.get(0).value()).isEqualTo(new BigDecimal("70.5"));
        assertThat(facts.get(0).unit()).isEqualTo("kg");
    }

    @Test
    @DisplayName("空腹血糖：6.5 mmol/L")
    void fastingGlucose() {
        assertThat(keys("空腹血糖6.5")).containsExactly("glucose");
    }

    @Test
    @DisplayName("睡眠：睡了6小时")
    void sleepHours() {
        assertThat(keys("昨晚只睡了5小时")).containsExactly("sleep_hours");
    }

    @Test
    @DisplayName("步数：走了8000步（3-6 位数字）")
    void steps() {
        var facts = HealthFactExtractor.extract("今天走了12000步");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).metricKey()).isEqualTo("steps");
        assertThat(facts.get(0).value()).isEqualTo(new BigDecimal("12000"));
    }

    @Test
    @DisplayName("多指标混合一句话全部抽取")
    void multipleMetrics() {
        var k = keys("我血压140/90，体重80，心率75，睡眠6小时");
        assertThat(k).contains("systolic", "diastolic", "weight", "heart_rate", "sleep_hours");
    }

    @Test
    @DisplayName("无指标陈述：空结果")
    void noMetrics() {
        assertThat(HealthFactExtractor.extract("今天天气不错")).isEmpty();
        assertThat(HealthFactExtractor.extract("")).isEmpty();
        assertThat(HealthFactExtractor.extract(null)).isEmpty();
    }

    @Test
    @DisplayName("同名指标去重：保留首次出现")
    void dedupSameKey() {
        var facts = HealthFactExtractor.extract("血压150/95，又量了一次血压140/88");
        assertThat(facts.stream().filter(f -> "systolic".equals(f.metricKey()))).hasSize(1);
        assertThat(facts.get(0).value()).isEqualTo(new BigDecimal("150"));
    }

    @Test
    @DisplayName("非法数值防御：0 或超大值不抽取")
    void invalidValues() {
        assertThat(HealthFactExtractor.extract("体重0")).isEmpty();
        assertThat(HealthFactExtractor.extract("走了999999步")).isEmpty();
    }
}
