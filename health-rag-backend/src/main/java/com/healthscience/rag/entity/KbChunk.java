package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档分块，对应 {@code kb_chunk} 表（ADR-023 后的核心改造表）。
 *
 * <p><b>向量内嵌</b>：{@code embedding VECTOR(1024)} 直接落在本表，分块与向量化
 * 同库同事务（消除 V1 双存储一致性缺陷）；NULL 表示未向量化，
 * {@code COUNT(*) FILTER (WHERE embedding IS NULL)} 即重建进度真值。
 * 实体不映射 embedding 列（1024 维文本体量大，列表/重建查询一律用投影 SQL 按需读写）。
 *
 * <p>{@code tsv} 生成列（zhparser + GIN）承载关键词召回，与 content 永不失同步。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    /** 块序号，同一文档内从 0 开始 */
    private Integer chunkIndex;
    /** 章节路径（结构感知分块产出；V1 JSONL 历史语料为 NULL） */
    private String section;
    private String content;
    private Integer charCount;
    /**
     * pgvector 文本（{@code [v1,v2,...]}，1024 维）。
     * {@code select = false}：默认查询不拖 1024 维文本（列表/重建路径用投影 SQL 按需读写），
     * INSERT / 显式 UPDATE 照常写入 —— 这正是"分块与向量同一条语句落库"（P1 根治）的落点。
     */
    @TableField(select = false)
    private String embedding;
    /** 元数据 JSON：url / 发布日期 / 关键词 / 来源层级 等，供引用溯源展示 */
    private String metaJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Boolean deleted;
}
