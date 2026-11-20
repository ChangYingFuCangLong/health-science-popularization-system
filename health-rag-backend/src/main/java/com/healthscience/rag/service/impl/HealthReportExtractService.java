package com.healthscience.rag.service.impl;

import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.entity.MetricDef;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.mapper.HealthKvMapper;
import com.healthscience.rag.mapper.HealthMetricMapper;
import com.healthscience.rag.mapper.MetricDefMapper;
import com.healthscience.rag.entity.HealthKv;
import com.healthscience.rag.entity.HealthMetric;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 体检报告识读（B-30，契约 11 号 #63/#64）。
 *
 * <p><b>流程</b>：Tika 抽文本 → qwen flash 温度 0 结构化抽取（prompt 内置目录 key 映射）
 * → 草稿内存缓存 TTL 10min（不入库不落盘，取消=零痕迹）→ 用户确认提交
 * （数值按目录校验入 health_metric source=report，越界跳过计 skipped；
 * 文本入 health_kv；reportDate 作为 recordedAt）。
 *
 * <p><b>降级纪律</b>：LLM 失败/输出异常 → 草稿仅含纯文本摘要项（type=text），
 * 用户仍可手动录入——抽取是增强，不是闸门。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthReportExtractService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 草稿 TTL（11 号：10 分钟） */
    private static final long DRAFT_TTL_MS = 10 * 60 * 1000L;
    /** 送入 LLM 的报告文本上限（字符）：长报告截断，控制 token */
    private static final int REPORT_TEXT_MAX = 6000;

    private final com.healthscience.rag.ai.impl.ReportLlmExtractor llmExtractor;
    private final MetricDefMapper metricDefMapper;
    private final MetricCatalogService catalogService;
    private final HealthMetricMapper metricMapper;
    private final HealthKvMapper kvMapper;
    private final ObjectMapper objectMapper;

    /** 草稿缓存：draftId → {expiry, userId, items, reportDate, kbDocumentId} */
    private final Map<String, Map<String, Object>> drafts = new ConcurrentHashMap<>();

    /** 抽取候选项（#63 响应 / #64 请求同构） */
    public record DraftItem(String key, String value, String value2, String type,
                            String metricKey, String unit, Double confidence) {
    }

    // ==================== #63 抽取 ====================

    public Map<String, Object> extract(Long userId, byte[] content, String filename,
                                       String ext, Long kbDocumentId) {
        String text = extractText(content, ext);
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.KB_FILE_INVALID, "未提取到有效文本");
        }
        String clipped = text.length() > REPORT_TEXT_MAX
                ? text.substring(0, REPORT_TEXT_MAX) : text;

        List<DraftItem> items = new ArrayList<>();
        String reportDate = null;
        try {
            String json = llmExtractor.extract(clipped, extractPrompt());
            Map<String, Object> parsed = parseJson(json);
            if (parsed != null) {
                reportDate = str(parsed.get("reportDate"));
                Object raw = parsed.get("items");
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            items.add(toItem(m));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ReportExtract] LLM 抽取失败，降级为纯文本草稿: {}", e.getMessage());
        }
        if (items.isEmpty()) {
            // 降级：报告摘要作为文本项，用户可手动录入
            items.add(new DraftItem("报告摘要", clip(text, 400), null,
                    "text", null, null, 0.3));
        }

        String draftId = "d_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> draft = new java.util.HashMap<>();   // 允许 null 值（reportDate 可能 null）
        draft.put("userId", userId);
        draft.put("expiry", System.currentTimeMillis() + DRAFT_TTL_MS);
        draft.put("items", items);
        draft.put("reportDate", reportDate);
        draft.put("kbDocumentId", kbDocumentId);
        drafts.put(draftId, draft);
        evictExpired();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("draftId", draftId);
        resp.put("kbDocumentId", kbDocumentId);
        resp.put("reportDate", reportDate);
        resp.put("items", items);
        return resp;
    }

    // ==================== #64 确认入库 ====================

    @SuppressWarnings("unchecked")
    public Map<String, Object> commit(Long userId, String draftId, String reportDate,
                                      List<DraftItem> items) {
        Map<String, Object> draft = drafts.get(draftId);
        if (draft == null || System.currentTimeMillis() > (long) draft.get("expiry")
                || !userId.equals(draft.get("userId"))) {
            throw BizException.badRequest("草稿已过期，请重新上传");
        }
        LocalDate recorded = parseDate(reportDate != null ? reportDate : str(draft.get("reportDate")));
        LocalDateTime recordedAt = recorded != null ? recorded.atStartOfDay() : LocalDateTime.now();

        int metricCount = 0;
        int kvCount = 0;
        int skipped = 0;
        List<String> skippedKeys = new ArrayList<>();
        for (DraftItem item : items == null ? List.<DraftItem>of() : items) {
            if (item == null || item.value() == null || item.value().isBlank()) {
                continue;
            }
            if ("metric".equals(item.type()) && item.metricKey() != null) {
                MetricDef def = catalogService.resolve(userId, item.metricKey());
                BigDecimal value = toDecimal(item.value());
                BigDecimal value2 = toDecimal(item.value2());
                if (def == null || value == null) {
                    skipped++;
                    skippedKeys.add(item.key());
                    continue;
                }
                try {
                    catalogService.validateRange(def, value);
                    if (value2 != null && Boolean.TRUE.equals(def.getHasValue2())) {
                        catalogService.validateRange(def, value2);
                    }
                } catch (BizException e) {
                    skipped++;   // 越界跳过不整批失败（11 号）
                    skippedKeys.add(item.key());
                    continue;
                }
                HealthMetric m = new HealthMetric();
                m.setUserId(userId);
                m.setType(def.getMetricKey());
                m.setValue(value);
                m.setValue2(value2);
                m.setRecordedAt(recordedAt);
                m.setNote("体检报告");
                m.setSource("report");
                LocalDateTime now = LocalDateTime.now();
                m.setCreateTime(now);
                m.setUpdateTime(now);
                metricMapper.insert(m);
                metricCount++;
            } else {
                HealthKv kv = new HealthKv();
                kv.setUserId(userId);
                kv.setItemKey(clip(item.key(), 50));
                // label 列已随现库退役
        kv.setItemKey(clip(item.key(), 50));
                kv.setItemValue(clip(item.value(), 500));
                kv.setSource("report");
                LocalDateTime now = LocalDateTime.now();
                kv.setCreateTime(now);
                kv.setUpdateTime(now);
                kvMapper.insert(kv);
                kvCount++;
            }
        }
        drafts.remove(draftId);   // 一次性：提交后草稿作废
        log.info("[ReportExtract] 提交完成 userId={}：指标 {} 条 / 备忘 {} 条 / 跳过 {}（{}）",
                userId, metricCount, kvCount, skipped, skippedKeys);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("metricCount", metricCount);
        resp.put("kvCount", kvCount);
        resp.put("skipped", skipped);
        resp.put("skippedKeys", skippedKeys);
        resp.put("kbDocumentId", draft.get("kbDocumentId"));
        return resp;
    }

    // ==================== LLM 抽取 ====================

    /** prompt 内置标准目录 key 映射（10 号 §1.1），随目录库动态生成 */
    private String extractPrompt() {
        List<MetricDef> basics = metricDefMapper.selectList(new LambdaQueryWrapper<MetricDef>()
                .isNull(MetricDef::getOwnerUserId)
                .eq(MetricDef::getDeleted, false));
        String catalog = basics.stream()
                .map(d -> d.getMetricKey() + "=" + d.getLabel()
                        + (Boolean.TRUE.equals(d.getHasValue2()) ? "（双值：值1/值2）" : ""))
                .collect(Collectors.joining("\n"));
        return """
                你是体检报告结构化抽取器。从报告中抽取健康指标与重要结论，输出严格 JSON（不要 markdown 代码块）：
                {"reportDate":"yyyy-MM-dd 或 null","items":[
                  {"key":"报告原文指标名","value":"数值字符串","value2":"第二数值或null","type":"metric|text",
                   "metricKey":"目录映射key或null","unit":"单位","confidence":0.0~1.0}]}
                规则：
                1. 数值型指标 type=metric，metricKey 只能取以下目录映射（无法映射则 null）：
                %s
                2. 血压等双值指标：value=收缩压 value2=舒张压；
                3. 结节/结论/建议等文本信息 type=text；
                4. 只抽取报告明确给出的项，不要推算；置信度如实给。
                """.formatted(catalog);
    }

    private DraftItem toItem(Map<?, ?> m) {
        return new DraftItem(
                str(m.get("key")), str(m.get("value")), str(m.get("value2")),
                "metric".equals(str(m.get("type"))) ? "metric" : "text",
                str(m.get("metricKey")), str(m.get("unit")),
                m.get("confidence") == null ? null : Double.parseDouble(str(m.get("confidence"))));
    }

    private Map<String, Object> parseJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        // 容错：剥掉可能的 ```json 包裹
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readValue(s.substring(start, end + 1), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Tika 抽文本 ====================

    private String extractText(byte[] content, String ext) {
        try {
            if ("txt".equals(ext) || "md".equals(ext) || "json".equals(ext)) {
                return new String(content, StandardCharsets.UTF_8);
            }
            var parser = new org.apache.tika.parser.AutoDetectParser();
            var out = new java.io.ByteArrayOutputStream();
            var handler = new org.apache.tika.sax.ToXMLContentHandler(out, "UTF-8");
            var metadata = new org.apache.tika.metadata.Metadata();
            try (var in = new java.io.ByteArrayInputStream(content)) {
                parser.parse(in, handler, metadata);
            }
            return out.toString(StandardCharsets.UTF_8)
                    .replaceAll("<[^>]+>", " ")     // XHTML 标签剥离（报告只要文本）
                    .replaceAll("\\s{2,}", " ")
                    .trim();
        } catch (Exception e) {
            log.warn("[ReportExtract] Tika 解析失败: {}", e.getMessage());
            throw new BizException(ErrorCode.DOC_PARSE_FAILED, "文档解析失败");
        }
    }

    // ==================== 工具 ====================

    private void evictExpired() {
        long now = System.currentTimeMillis();
        drafts.entrySet().removeIf(e -> (long) e.getValue().get("expiry") < now);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal toDecimal(String s) {
        if (s == null) {
            return null;
        }
        try {
            Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(s.trim());
            return m.find() ? new BigDecimal(m.group()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null || "null".equals(String.valueOf(v)) ? null : String.valueOf(v).trim();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
