package com.healthscience.rag.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话健康事实规则抽取器（B-28 二次完善，08 §2.5）。
 *
 * <p>词表 + 正则，命中"指标词 + 数值"即抽取；<b>零 LLM 成本、可解释、纯函数</b>。
 * 误报不纠正（只作上下文参考，不写回档案）；LLM 抽取列为演进项。
 */
public final class HealthFactExtractor {

    /** 抽取结果：metric_key 引用指标目录（B-29），value 为数值 */
    public record ExtractedFact(String metricKey, BigDecimal value, String unit) {
    }

    private static final String NUM = "(\\d{1,3}(?:\\.\\d)?)";

    /** 词表 + 正则（顺序即优先级；血压双值最先，避免被单值规则抢走收缩压） */
    private static final Pattern[] PATTERNS = {
            // 血压 145/95 → 收缩压 + 舒张压
            Pattern.compile("血压(?:是|为|大概|大约)?\\s*" + NUM + "\\s*[/-]\\s*" + NUM),
            // 收缩压 145 / 高压 145
            Pattern.compile("(?:收缩压|高压)(?:是|为)?\\s*" + NUM),
            // 舒张压 95 / 低压 95
            Pattern.compile("(?:舒张压|低压)(?:是|为)?\\s*" + NUM),
            // 体重 70.5（公斤/kg/千克）
            Pattern.compile("体重(?:是|为|大概|大约)?\\s*" + NUM + "\\s*(?:公斤|kg|千克)?"),
            // 空腹血糖 6.5 / 血糖 6.5
            Pattern.compile("(?:空腹)?血糖(?:是|为)?\\s*" + NUM),
            // 心率 88
            Pattern.compile("心率(?:是|为)?\\s*" + NUM),
            // 睡了 6 小时 / 睡眠 6.5 小时
            Pattern.compile("(?:睡了|睡眠)(?:是|为|大概|大约)?\\s*" + NUM + "\\s*(?:个?)小时"),
            // 走了 8000 步 / 步数 8000
            Pattern.compile("(?:走了|步数(?:是|为)?|步行)\\s*(\\d{3,6})\\s*步"),
    };

    private static final String[][] KEYS_UNITS = {
            {"systolic", "mmHg", "diastolic", "mmHg"},
            {"systolic", "mmHg"},
            {"diastolic", "mmHg"},
            {"weight", "kg"},
            {"glucose", "mmol/L"},
            {"heart_rate", "bpm"},
            {"sleep_hours", "h"},
            {"steps", "步"},
    };

    private HealthFactExtractor() {
    }

    /**
     * 从一段用户陈述中抽取健康指标事实（同名指标去重，保留首次）。
     *
     * @return 抽取结果（可能为空列表）；永不抛出
     */
    public static List<ExtractedFact> extract(String text) {
        List<ExtractedFact> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Map<String, ExtractedFact> byKey = new LinkedHashMap<>();
        for (int p = 0; p < PATTERNS.length; p++) {
            Matcher m = PATTERNS[p].matcher(text);
            String[] ku = KEYS_UNITS[p];
            int ki = 0;
            while (m.find()) {
                for (int group = 1; group <= m.groupCount(); group++) {
                    if (ki >= ku.length) {
                        break;
                    }
                    String key = ku[ki];
                    BigDecimal value = parse(m.group(group));
                    ki += 2;
                    if (value != null && !byKey.containsKey(key)) {
                        ExtractedFact fact = new ExtractedFact(key, value, ku[ki - 1]);
                        byKey.put(key, fact);
                        out.add(fact);
                    }
                }
            }
        }
        return out;
    }

    private static BigDecimal parse(String s) {
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.doubleValue() <= 0 || v.doubleValue() > 100000) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
