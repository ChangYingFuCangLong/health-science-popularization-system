package com.healthscience.rag.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 健康问答安全策略（任务卡 B-16，两道闸门）。
 *
 * <p><b>闸门 ① 前置意图拦截</b>：命中危机/急救类意图（自杀自伤、急性中毒、
 * 大出血等 ≥10 类）时<b>不检索、不生成</b>，直接返回固定安全话术
 * （error 2004 + 就医/热线引导，{@code is_rejected=true} 落库）。
 * 用规则而非模型：毫秒级、可解释、可控 —— 危机场景容不得模型幻觉。
 *
 * <p><b>闸门 ② 置信度拒答</b>：向量路 top1 余弦相似度低于阈值时拒答
 * （error 2002，{@code is_rejected=true} 落库），避免对知识库未覆盖的问题硬答。默认 0.45（实测校准点：真实命中 0.72 / 无关乱串 0.40，精确校准留 M13）。
 * ⚠️ 判定必须用<b>向量路原始余弦分数</b>（∈[-1,1]，语义直观），
 * 不能用 RRF 分数（名次倒数和，分数域无语义）。
 *
 * <p>拒答阈值经评测集校准后在管理端可调；拦截止前给出统一引导话术，
 * 引导用户换个问法或就医，而不是让模型编造。
 */
@Slf4j
@Component
public class SafetyPolicy {

    /** 2004 敏感拦截统一话术（契约 02 §4.6：含安全引导） */
    public static final String CRISIS_MESSAGE =
            "你提到的内容可能涉及紧急或危机情况，我无法替代专业救助。请立即："
                    + "①拨打 120 急救电话或前往最近的医院急诊科；"
                    + "②如有心理危机，可拨打全国心理援助热线 12356（24 小时）；"
                    + "③告诉身边可信任的人，让TA陪着你。生命安全永远是第一位的。";

    /** 2002 拒答统一话术（契约 02 §4.6：前端展示统一文案并引导换问法） */
    public static final String REJECT_MESSAGE = "知识库中暂无相关内容，换个问法试试";

    /** 敏感意图规则库：危机/急救类，≥10 类（新增类别追加即可，正则见注释分类名） */
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            // 1 自杀自伤意念
            // 2026-10-25 补：跳楼/跳桥/跳河/上吊/烧炭/自尽 与想死/寻死/求死（原规则漏判，测试报告 §21.4）
            Pattern.compile("自杀|轻生|不想活|活不下去|结束生命|了结自己|自残|自伤|割腕|划伤自己"
                    + "|跳楼|跳桥|跳河|上吊|烧炭|自尽|想死|寻死|求死"),
            // 2 急性中毒 / 过量服药
            // 2026-10-25 补：问剂量型（"吃多少安眠药会死"）原规则要求动词或"自杀"字样，匹配不到；
            // 注意只锁「剂量 + 致死」组合，避免误伤"服用安眠药要注意什么"这类正常用药咨询
            Pattern.compile("服毒|喝.{0,2}农药|农药中毒|吞.{0,6}安眠药|吃.{0,6}安眠药自杀|药物过量|大量吞药|误食毒"
                    + "|(多少|几片|几颗|几粒).{0,6}安眠药|(吃|吞|服).{0,6}安眠药.{0,6}(死|过量|超量)"),
            // 3 大出血 / 严重外伤
            Pattern.compile("大出血|血流不止|动脉出血|开放性伤口|伤口喷血|出血止不住"),
            // 4 心跳呼吸骤停 / 晕厥窒息
            Pattern.compile("心脏骤停|呼吸骤停|突然晕倒|突然昏倒|晕厥|窒息|喘不上气说不出话"),
            // 5 儿童/婴儿急症
            Pattern.compile("孩子高热惊厥|小孩抽搐不停|婴儿呼吸困难|新生儿不吃奶不哭闹"),
            // 6 妊娠紧急
            Pattern.compile("孕妇大出血|怀孕出血不止|临产紧急|宫缩剧烈出血"),
            // 7 过敏性休克 / 喉头水肿
            Pattern.compile("过敏性休克|喉头水肿|全身荨麻疹.{0,6}呼吸困难"),
            // 8 胸痛急症（心梗）
            Pattern.compile("胸痛.{0,6}压榨|心梗发作|胸口剧痛.{0,6}出冷汗|心前区剧痛"),
            // 9 卒中急症（FAST 征）
            Pattern.compile("口眼歪斜|突然说话不清|口角歪斜|一侧肢体无力|突然看不清东西.{0,6}一侧"),
            // 10 大面积烧烫伤 / 触电 / 溺水
            Pattern.compile("大面积烧伤|大面积烫伤|触电了|溺水|电击伤"),
            // 11 剧烈腹痛 / 消化道急症
            Pattern.compile("剧烈腹痛.{0,6}呕血|呕血|便血.{0,6}头晕|喷射状呕吐"),
            // 12 毒品相关
            Pattern.compile("吸毒|冰毒|海洛因")
    );

    private final double rejectThreshold;
    private final boolean preFilterEnabled;

    public SafetyPolicy(
            @Value("${health.rag.safety.reject-threshold:0.45}") double rejectThreshold,
            @Value("${health.rag.safety.pre-filter-enabled:true}") boolean preFilterEnabled) {
        this.rejectThreshold = rejectThreshold;
        this.preFilterEnabled = preFilterEnabled;
        log.info("[SafetyPolicy] 已启用：rejectThreshold={}, preFilterEnabled={}", rejectThreshold, preFilterEnabled);
    }

    /**
     * 闸门 ①：问题是否命中危机/急救意图。
     * {@code health.rag.safety.pre-filter-enabled=false} 时整体关闭（消融实验用）。
     */
    public boolean isSensitive(String question) {
        if (!preFilterEnabled || question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 闸门 ②：向量路 top1 余弦相似度是否低于拒答阈值。
     *
     * @param vectorTopScore 向量路最高余弦相似度；没有向量命中时调用方传 0（必然拒答）
     */
    public boolean belowConfidence(double vectorTopScore) {
        return vectorTopScore < rejectThreshold;
    }

    /** 当前拒答阈值（检索回放接口展示用） */
    public double rejectThreshold() {
        return rejectThreshold;
    }
}
