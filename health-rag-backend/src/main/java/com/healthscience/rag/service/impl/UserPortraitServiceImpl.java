package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.entity.UserPortrait;
import com.healthscience.rag.mapper.UserPortraitMapper;
import com.healthscience.rag.model.dto.UserPortraitDTO;
import com.healthscience.rag.service.UserPortraitService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 健康画像实现（M16，契约 08）。
 *
 * <p><b>中文标签的唯一来源</b>：本类的字典表同时服务 #56 字典接口与 {@link #promptSection}
 * 的注入文本——存英文码、出中文，两处共用一份，杜绝漂移。
 *
 * <p><b>UPSERT 并发</b>：一人一条由唯一索引兜底；并发首次保存时 INSERT 撞索引，
 * 捕获后转为按已有行更新（不能把 DataIntegrityViolationException 冒成 500）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPortraitServiceImpl implements UserPortraitService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserPortraitMapper portraitMapper;
    private final ObjectMapper objectMapper;

    // ---------- 字段字典（value → 中文；#56 与注入文本共用） ----------

    private static final Map<String, String> GENDERS = dictOf("male", "男", "female", "女", "other", "其他");
    private static final Map<String, String> CONDITIONS = dictOf(
            "hypertension", "高血压", "diabetes", "糖尿病", "hyperlipidemia", "高血脂",
            "hyperuricemia", "高尿酸·痛风", "chd", "冠心病", "asthma", "哮喘",
            "thyroid", "甲状腺疾病", "fatty_liver", "脂肪肝");
    private static final Map<String, String> EXERCISE = dictOf(
            "never", "几乎不运动", "rare", "偶尔", "weekly1_2", "每周 1~2 次",
            "weekly3_5", "每周 3~5 次", "daily", "几乎每天");
    private static final Map<String, String> SMOKING = dictOf(
            "never", "从不", "quit", "已戒", "light", "偶尔", "heavy", "每天");
    private static final Map<String, String> DRINKING = dictOf(
            "never", "从不", "rarely", "很少", "social", "社交场合", "frequent", "经常");
    private static final Map<String, String> DIET = dictOf(
            "low_salt", "低盐", "low_sugar", "低糖", "low_fat", "低脂",
            "high_protein", "高蛋白", "vegetarian", "素食", "irregular", "三餐不规律");
    private static final Map<String, String> GOALS = dictOf(
            "lose_weight", "减重", "gain_muscle", "增肌", "control_bp", "控血压",
            "control_sugar", "控血糖", "control_lipid", "降血脂", "better_sleep", "改善睡眠",
            "quit_smoking", "戒烟", "keep_fit", "保持健康");

    private static Map<String, String> dictOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---------- #53 / #54 / #55 ----------

    @Override
    public UserPortraitDTO.PortraitVO get(Long userId) {
        UserPortrait p = portraitMapper.selectOne(oneOf(userId));
        if (p == null) {
            return new UserPortraitDTO.PortraitVO(null, null, null, null, List.of(), null, null,
                    null, null, null, null, List.of(), List.of(), null,
                    0, 0, TOTAL_FIELDS, null, false);
        }
        List<String> conditions = readList(p.getConditions());
        List<String> diet = readList(p.getDiet());
        List<String> goals = readList(p.getGoals());
        int filled = filledCount(p, conditions, diet, goals);
        return new UserPortraitDTO.PortraitVO(
                p.getGender(), p.getBirthYear(), p.getHeightCm(), p.getWeightKg(),
                conditions, p.getAllergies(), p.getMedications(),
                p.getExercise(), p.getSleepHours(), p.getSmoking(), p.getDrinking(),
                diet, goals, p.getNote(),
                filled * 100 / TOTAL_FIELDS, filled, TOTAL_FIELDS,
                p.getUpdateTime() == null ? null : p.getUpdateTime().format(TIME_FORMAT), true);
    }

    @Override
    public void save(Long userId, UserPortraitDTO.SaveRequest request) {
        UserPortrait entity = new UserPortrait();
        entity.setUserId(userId);
        entity.setGender(request.gender());
        entity.setBirthYear(request.birthYear());
        entity.setHeightCm(request.heightCm());
        entity.setWeightKg(request.weightKg());
        entity.setConditions(writeList(request.conditions()));
        entity.setAllergies(request.allergies());
        entity.setMedications(request.medications());
        entity.setExercise(request.exercise());
        entity.setSleepHours(request.sleepHours());
        entity.setSmoking(request.smoking());
        entity.setDrinking(request.drinking());
        entity.setDiet(writeList(request.diet()));
        entity.setGoals(writeList(request.goals()));
        entity.setNote(request.note());

        UserPortrait exist = portraitMapper.selectOne(oneOf(userId));
        if (exist == null) {
            LocalDateTime now = LocalDateTime.now();
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            try {
                portraitMapper.insert(entity);
                return;
            } catch (DataIntegrityViolationException e) {
                // 并发首次保存撞 uk_portrait_user：转为按已有行更新
                log.info("[Portrait] 并发首次保存撞唯一索引，转更新 userId={}", userId);
                exist = portraitMapper.selectOne(oneOf(userId));
                if (exist == null) {
                    throw e;
                }
            }
        }
        entity.setId(exist.getId());
        entity.setCreateTime(exist.getCreateTime());
        entity.setUpdateTime(LocalDateTime.now());
        portraitMapper.updateById(entity);
    }

    @Override
    public void delete(Long userId) {
        // 物理删除（无 deleted 列）；不级联删除专属对话，后果由前端提示（08 §5.3 #55）
        portraitMapper.delete(oneOf(userId));
    }

    @Override
    public UserPortraitDTO.OptionsVO options() {
        return new UserPortraitDTO.OptionsVO(optionsOf(GENDERS), optionsOf(CONDITIONS),
                optionsOf(EXERCISE), optionsOf(SMOKING), optionsOf(DRINKING),
                optionsOf(DIET), optionsOf(GOALS));
    }

    // ---------- 生成链路支撑 ----------

    @Override
    public String promptSection(Long userId) {
        UserPortrait p = portraitMapper.selectOne(oneOf(userId));
        if (p == null) {
            return null;
        }
        List<String> conditions = readList(p.getConditions());
        List<String> diet = readList(p.getDiet());
        List<String> goals = readList(p.getGoals());
        if (filledCount(p, conditions, diet, goals) == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<String> parts = new ArrayList<>();
        if (p.getGender() != null) {
            parts.add("性别：" + GENDERS.getOrDefault(p.getGender(), p.getGender()));
        }
        if (p.getBirthYear() != null) {
            parts.add("出生年份：" + p.getBirthYear());
        }
        if (p.getHeightCm() != null) {
            parts.add("身高：" + p.getHeightCm() + "cm");
        }
        if (p.getWeightKg() != null) {
            parts.add("体重：" + p.getWeightKg() + "kg");
        }
        if (!conditions.isEmpty()) {
            parts.add("慢性病：" + labeled(CONDITIONS, conditions));
        }
        if (p.getAllergies() != null && !p.getAllergies().isBlank()) {
            parts.add("过敏：" + p.getAllergies());
        }
        if (p.getMedications() != null && !p.getMedications().isBlank()) {
            parts.add("正在用药：" + p.getMedications());
        }
        if (p.getExercise() != null) {
            parts.add("运动频率：" + EXERCISE.getOrDefault(p.getExercise(), p.getExercise()));
        }
        if (p.getSleepHours() != null) {
            parts.add("睡眠：" + p.getSleepHours() + " 小时");
        }
        if (p.getSmoking() != null) {
            parts.add("吸烟：" + SMOKING.getOrDefault(p.getSmoking(), p.getSmoking()));
        }
        if (p.getDrinking() != null) {
            parts.add("饮酒：" + DRINKING.getOrDefault(p.getDrinking(), p.getDrinking()));
        }
        if (!diet.isEmpty()) {
            parts.add("饮食偏好：" + labeled(DIET, diet));
        }
        if (!goals.isEmpty()) {
            parts.add("健康目标：" + labeled(GOALS, goals));
        }
        if (p.getNote() != null && !p.getNote().isBlank()) {
            parts.add("补充说明：" + p.getNote());
        }
        sb.append("【用户健康画像】以下为用户自行填写的个人健康信息，回答时请结合这些情况给出个体化建议（涉及用药与诊疗仍须建议咨询医生）：\n");
        sb.append(String.join("；", parts));
        if (sb.length() > PROMPT_MAX_CHARS) {
            sb.setLength(PROMPT_MAX_CHARS);
        }
        return sb.toString();
    }

    @Override
    public boolean hasContent(Long userId) {
        UserPortrait p = portraitMapper.selectOne(oneOf(userId));
        if (p == null) {
            return false;
        }
        return filledCount(p, readList(p.getConditions()), readList(p.getDiet()),
                readList(p.getGoals())) > 0;
    }

    // ---------- 工具 ----------

    private static LambdaQueryWrapper<UserPortrait> oneOf(Long userId) {
        return new LambdaQueryWrapper<UserPortrait>().eq(UserPortrait::getUserId, userId);
    }

    /** 完善度口径：14 个字段逐一判空（数组字段按非空列表计） */
    private int filledCount(UserPortrait p, List<String> conditions, List<String> diet, List<String> goals) {
        int n = 0;
        if (p.getGender() != null) n++;
        if (p.getBirthYear() != null) n++;
        if (p.getHeightCm() != null) n++;
        if (p.getWeightKg() != null) n++;
        if (!conditions.isEmpty()) n++;
        if (p.getAllergies() != null && !p.getAllergies().isBlank()) n++;
        if (p.getMedications() != null && !p.getMedications().isBlank()) n++;
        if (p.getExercise() != null) n++;
        if (p.getSleepHours() != null) n++;
        if (p.getSmoking() != null) n++;
        if (p.getDrinking() != null) n++;
        if (!diet.isEmpty()) n++;
        if (!goals.isEmpty()) n++;
        if (p.getNote() != null && !p.getNote().isBlank()) n++;
        return n;
    }

    private static List<UserPortraitDTO.OptionVO> optionsOf(Map<String, String> dict) {
        return dict.entrySet().stream()
                .map(e -> new UserPortraitDTO.OptionVO(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private static String labeled(Map<String, String> dict, List<String> codes) {
        return codes.stream()
                .map(c -> dict.getOrDefault(c, c))
                .collect(Collectors.joining("、"));
    }

    private String writeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
