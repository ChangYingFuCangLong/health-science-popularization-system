package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 消融实验结果，对应 {@code eval_result} 表。
 *
 * <p><b>论文数据的唯一来源</b>：接口文档 §7.5 明确要求，
 * 论文「实验与评估」章节的数据必须来自本表的真实运行记录，不得引用文档示例。
 *
 * <p>{@code faithfulness} 与 {@code citationCorrect} 采用<b>双轨制</b>（02 §八 B-22）：
 * LLM-as-judge 自动打分并置 {@code scoreSource='AUTO'}，人工复核覆盖后置 {@code 'MANUAL'}。
 * 论文须同时声明两条轨道并给出抽检一致率，只引用自动分不足以支撑结论。
 */
@Data
@TableName("eval_result")
public class EvalResult {

    /** 自动判分（LLM-as-judge）来源标识 */
    public static final String SOURCE_AUTO = "AUTO";
    /** 人工复核来源标识 */
    public static final String SOURCE_MANUAL = "MANUAL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String batchNo;
    private Long datasetId;
    private String strategy;
    /** 召回片段及排序的 JSON：[{chunkId,rank,score}] */
    private String retrievedJson;
    /** 首个命中片段的排名；未命中为 null */
    private Integer hitRank;
    private Boolean isHit;
    private String answer;
    private BigDecimal faithfulness;
    private BigDecimal citationCorrect;
    /** AUTO=LLM 判分 / MANUAL=人工复核 / null=未评分（历史批次均为此值，论文不得引用） */
    private String scoreSource;
    private Integer latencyMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Boolean deleted;
}
