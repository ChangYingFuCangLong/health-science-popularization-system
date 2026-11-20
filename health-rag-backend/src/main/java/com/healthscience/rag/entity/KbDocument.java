package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档，对应 {@code kb_document} 表。
 *
 * <p><b>状态机</b>：{@code 0 待处理 → 1 解析中 → 2 已完成 / 3 失败}。
 * 异步任务执行中若服务重启，状态会永远停在 1 —— 启动时需扫描并重置超时的记录
 * （见 {@code 系统设计.md} 失败模式表）。
 */
@Data
@TableName("kb_document")
public class KbDocument {

    /** 归属：全局库（管理端维护，所有用户可见） */
    public static final String SCOPE_GLOBAL = "global";
    /** 归属：个人库（用户自有资料，仅 owner 可见，M15） */
    public static final String SCOPE_PERSONAL = "personal";

    /** 待处理 */
    public static final int STATUS_PENDING = 0;
    /** 解析中 */
    public static final int STATUS_PARSING = 1;
    /** 已完成 */
    public static final int STATUS_DONE = 2;
    /** 失败 */
    public static final int STATUS_FAILED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    /** 来源机构，如「中国疾病预防控制中心」 */
    private String source;
    /**
     * 原文链接（溯源用，M14/A4）：语料入库时从采集 manifest 回填。
     *
     * <p>可空：早期 seed 批次（batch1）没有 manifest，无法溯源；用户自行上传的文档也留空。
     * 该字段只用于"这条知识从哪来"的展示与核查，不参与检索打分。
     */
    private String sourceUrl;
    /** 分类：疾病防治 / 传染病防控 / 慢性病防治 / 心理健康 ... */
    private String category;
    private String fileType;
    private String filePath;
    private Long fileSize;
    private Integer status;
    private String failReason;
    /** 归属（M15）：global 全局库 / personal 个人库；CHECK 约束强制二者与 ownerUserId 配对 */
    private String scope;
    /** 个人库 owner（scope=personal 时必填；global 恒为 NULL） */
    private Long ownerUserId;
    private Integer chunkCount;
    private Long uploadBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Boolean deleted;
}
