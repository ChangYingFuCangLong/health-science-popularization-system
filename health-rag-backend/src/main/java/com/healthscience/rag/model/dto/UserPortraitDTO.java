package com.healthscience.rag.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 健康画像传输对象（M16，契约 08 §5）。
 *
 * <p><b>存取分离</b>：库内英文枚举码，中文标签由 #56 字典接口下发，前端不硬编码。
 */
public final class UserPortraitDTO {

    private UserPortraitDTO() {
    }

    /** #54 保存请求（全量提交；全部字段可选，越界由服务端校验 → 400） */
    public record SaveRequest(
            @Pattern(regexp = "male|female|other", message = "性别取值不合法")
            String gender,

            @Min(value = 1900, message = "出生年份须在 1900~2100 之间")
            @Max(value = 2100, message = "出生年份须在 1900~2100 之间")
            Integer birthYear,

            @Min(value = 80, message = "身高须在 80~250 cm 之间")
            @Max(value = 250, message = "身高须在 80~250 cm 之间")
            Integer heightCm,

            @Min(value = 20, message = "体重须在 20~300 kg 之间")
            @Max(value = 300, message = "体重须在 20~300 kg 之间")
            BigDecimal weightKg,

            List<@Pattern(regexp = "hypertension|diabetes|hyperlipidemia|hyperuricemia|chd|asthma|thyroid|fatty_liver",
                    message = "慢病标签取值不合法") String> conditions,

            @Size(max = 300, message = "过敏信息不超过 300 字")
            String allergies,

            @Size(max = 500, message = "用药信息不超过 500 字")
            String medications,

            @Pattern(regexp = "never|rare|weekly1_2|weekly3_5|daily", message = "运动频率取值不合法")
            String exercise,

            @Min(value = 0, message = "睡眠时长须在 0~24 小时之间")
            @Max(value = 24, message = "睡眠时长须在 0~24 小时之间")
            BigDecimal sleepHours,

            @Pattern(regexp = "never|quit|light|heavy", message = "吸烟取值不合法")
            String smoking,

            @Pattern(regexp = "never|rarely|social|frequent", message = "饮酒取值不合法")
            String drinking,

            List<@Pattern(regexp = "low_salt|low_sugar|low_fat|high_protein|vegetarian|irregular",
                    message = "饮食偏好取值不合法") String> diet,

            List<@Pattern(regexp = "lose_weight|gain_muscle|control_bp|control_sugar|control_lipid|better_sleep|quit_smoking|keep_fit",
                    message = "目标标签取值不合法") String> goals,

            @Size(max = 1000, message = "补充说明不超过 1000 字")
            String note
    ) {
    }

    /** #53 读取响应 */
    public record PortraitVO(
            String gender,
            Integer birthYear,
            Integer heightCm,
            BigDecimal weightKg,
            List<String> conditions,
            String allergies,
            String medications,
            String exercise,
            BigDecimal sleepHours,
            String smoking,
            String drinking,
            List<String> diet,
            List<String> goals,
            String note,
            Integer completeness,
            Integer filledCount,
            Integer totalCount,
            String updateTime,
            Boolean exists
    ) {
    }

    /** #56 字典项 */
    public record OptionVO(String value, String label) {
    }

    /** #56 字段字典响应 */
    public record OptionsVO(
            List<OptionVO> genders,
            List<OptionVO> conditions,
            List<OptionVO> exercise,
            List<OptionVO> smoking,
            List<OptionVO> drinking,
            List<OptionVO> diet,
            List<OptionVO> goals
    ) {
    }
}
