package com.healthscience.rag.service.impl;

import com.healthscience.rag.mapper.ChatHealthFactMapper;
import com.healthscience.rag.mapper.HealthKvMapper;
import com.healthscience.rag.mapper.UserPortraitMapper;
import com.healthscience.rag.model.dto.UserPortraitDTO;
import com.healthscience.rag.service.HealthContextService;
import com.healthscience.rag.service.UserPortraitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 健康上下文组装实现（M16 v4.0 二次完善，08 §2.3/2.5）。
 *
 * <p>四层合并（备忘层 B-30 就绪后加入）：画像 ≤600 + 档案摘要 ≤200 + 会话事实 ≤300 + KV ≤300，
 * 总 ≤800 字。注入优先级通过"冲突时以本轮/新值为准"的提示语表达——系统不替用户改档案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthContextServiceImpl implements HealthContextService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final int PORTRAIT_MAX = 600;
    private static final int METRICS_MAX = 200;
    private static final int FACTS_MAX = 300;
    private static final int KV_MAX = 300;
    private static final int TOTAL_MAX = 800;

    /** 健康话题词表（healthSuggestion 判定用） */
    private static final Pattern HEALTH_TOPIC = Pattern.compile(
            "血压|血糖|体重|睡眠|心率|健康|症状|头疼|头晕|失眠|饮食|运动|用药|体检|慢病|减肥|戒烟");

    private static final Pattern REMINDER_MARK = Pattern.compile(
            "【HEALTH_REMINDER】(\\{.*?})【/HEALTH_REMINDER】", Pattern.DOTALL);
    private static final Pattern SUGGESTION_MARK = Pattern.compile(
            "【HEALTH_SUGGESTION】(.*?)【/HEALTH_SUGGESTION】", Pattern.DOTALL);

    private final UserPortraitMapper portraitMapper;
    private final UserPortraitService userPortraitService;
    private final com.healthscience.rag.mapper.HealthMetricMapper metricMapper;
    private final ChatHealthFactMapper factMapper;
    private final HealthKvMapper kvMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public String portraitSection(Long userId) {
        return userPortraitService.promptSection(userId);
    }

    @Override
    public String buildHealthContext(Long userId, Long sessionId) {
        List<String> sections = new ArrayList<>();
        String portrait = userPortraitService.promptSection(userId);
        if (portrait != null) {
            sections.add(clip(portrait, PORTRAIT_MAX));
        }
        String metrics = latestMetricsSection(userId);
        if (metrics != null) {
            sections.add(metrics);
        }
        if (sessionId != null) {
            String facts = factsSection(sessionId);
            if (facts != null) {
                sections.add(facts);
            }
        }
        String kv = kvSection(userId);
        if (kv != null) {
            sections.add(kv);
        }
        if (sections.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("【用户健康状况】以下为用户已授权使用的个人健康信息，");
        sb.append("回答请结合这些情况给出个体化建议；若发现用户本轮陈述与档案矛盾，按本轮陈述回答，");
        sb.append("并在回答末尾用【HEALTH_REMINDER】{\"field\":\"字段key\",\"recordedValue\":\"档案值\",");
        sb.append("\"statedValue\":\"本轮陈述值\"}【/HEALTH_REMINDER】标记一处矛盾（仅一处、单行 JSON）。\n");
        for (String s : sections) {
            sb.append(s).append('\n');
            if (sb.length() > TOTAL_MAX) {
                break;
            }
        }
        return sb.length() > TOTAL_MAX ? sb.substring(0, TOTAL_MAX) : sb.toString();
    }

    @Override
    public int extractAndSaveFacts(Long sessionId, Long userId, Long messageId, String text) {
        List<HealthFactExtractor.ExtractedFact> facts = HealthFactExtractor.extract(text);
        int n = 0;
        for (HealthFactExtractor.ExtractedFact f : facts) {
            try {
                com.healthscience.rag.entity.ChatHealthFact row = new com.healthscience.rag.entity.ChatHealthFact();
                row.setSessionId(sessionId);
                row.setUserId(userId);
                row.setMetricKey(f.metricKey());
                row.setValue(f.value());
                row.setUnit(f.unit());
                row.setMessageId(messageId);
                row.setCreateTime(java.time.LocalDateTime.now());
                factMapper.insert(row);
                n++;
            } catch (Exception e) {
                log.warn("[HealthContext] 健康事实落库失败: {}", e.getMessage());
            }
        }
        return n;
    }

    @Override
    public StripResult stripMarkers(String content) {
        if (content == null || content.isBlank()) {
            return new StripResult(content, null, null);
        }
        Map<String, Object> reminder = null;
        String suggestion = null;
        Matcher rm = REMINDER_MARK.matcher(content);
        if (rm.find()) {
            try {
                reminder = objectMapper.readValue(rm.group(1),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            } catch (Exception e) {
                log.warn("[HealthContext] reminder JSON 解析失败: {}", rm.group(1));
            }
        }
        Matcher sm = SUGGESTION_MARK.matcher(content);
        if (sm.find()) {
            suggestion = sm.group(1).trim();
        }
        String cleaned = REMINDER_MARK.matcher(content).replaceAll("");
        if (suggestion != null) {
            cleaned = SUGGESTION_MARK.matcher(cleaned).replaceAll("");
        } else {
            cleaned = cleaned.replace("【HEALTH_SUGGESTION】", "").replace("【/HEALTH_SUGGESTION】", "");
        }
        cleaned = cleaned.replace("【HEALTH_REMINDER】", "").replace("【/HEALTH_REMINDER】", "").trim();
        return new StripResult(cleaned, reminder, suggestion);
    }

    // ---------- 层② 档案最新值 ----------

    private String latestMetricsSection(Long userId) {
        try {
            List<Map<String, Object>> rows = metricMapper.selectLatestPerType(userId);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("【健康档案·最新值】");
            List<String> items = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                String type = String.valueOf(r.get("type"));
                String value = String.valueOf(r.get("value"));
                Object v2 = r.get("value2");
                String day = r.get("recorded_at") == null ? "" :
                        String.valueOf(r.get("recorded_at")).substring(5, 10);
                if (v2 != null) {
                    items.add(type + " " + value + "/" + v2 + "（" + day + "）");
                } else {
                    items.add(type + " " + value + "（" + day + "）");
                }
            }
            sb.append(String.join("；", items));
            return sb.length() > METRICS_MAX ? sb.substring(0, METRICS_MAX) : sb.toString();
        } catch (Exception e) {
            log.warn("[HealthContext] 档案最新值读取失败: {}", e.getMessage());
            return null;
        }
    }

    // ---------- 层③ 会话事实 ----------

    private String factsSection(Long sessionId) {
        try {
            List<Map<String, Object>> rows = factMapper.selectBySession(sessionId);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("【本会话中你提到的】");
            List<String> items = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                items.add(r.get("metricKey") + " " + r.get("value")
                        + (r.get("unit") == null ? "" : " " + r.get("unit")));
            }
            sb.append(String.join("；", items));
            return sb.length() > FACTS_MAX ? sb.substring(0, FACTS_MAX) : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 层④ 健康 KV ----------

    private String kvSection(Long userId) {
        try {
            List<com.healthscience.rag.entity.HealthKv> rows = kvMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.healthscience.rag.entity.HealthKv>()
                            .eq(com.healthscience.rag.entity.HealthKv::getUserId, userId)
                            .orderByDesc(com.healthscience.rag.entity.HealthKv::getUpdateTime)
                            .last("LIMIT 20"));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("【健康备忘】");
            List<String> items = new ArrayList<>();
            for (com.healthscience.rag.entity.HealthKv r : rows) {
                items.add(r.getItemKey() + "：" + r.getItemValue());
            }
            sb.append(String.join("；", items));
            return sb.length() > KV_MAX ? sb.substring(0, KV_MAX) : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
