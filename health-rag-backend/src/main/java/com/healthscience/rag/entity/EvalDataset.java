package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评测集（问答对），对应 {@code eval_dataset} 表。
 *
 * <p>{@code refChunkIds} 是人工标注的"应该命中哪些分块"，
 * 是计算 Hit Rate / MRR 的 ground truth —— 没有它就无法量化检索质量。
 */
@Data
@TableName("eval_dataset")
public class EvalDataset {

    /** 易 */
    public static final int DIFFICULTY_EASY = 1;
    /** 中 */
    public static final int DIFFICULTY_MEDIUM = 2;
    /** 难 */
    public static final int DIFFICULTY_HARD = 3;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String question;
    private String groundTruth;
    /** 参考片段 ID 的 JSON 数组：[1,5,12] */
    private String refChunkIds;
    private String category;
    private Integer difficulty;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Boolean deleted;
}
