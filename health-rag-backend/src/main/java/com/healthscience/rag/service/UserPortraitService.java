package com.healthscience.rag.service;

import com.healthscience.rag.model.dto.UserPortraitDTO;

/**
 * 健康画像（M16，契约 08 §5）。
 *
 * <p><b>隐私红线</b>：画像内容只允许存在于 user_portrait 表与请求期内存中的系统提示，
 * <b>绝不写入 chat_message 任何列</b>（08 §2.2）。
 * <b>只影响生成，不参与检索</b>（08 §2.1，保护消融实验可比性）。
 */
public interface UserPortraitService {

    /** 画像字段总数（completeness 口径，与字段字典一致） */
    int TOTAL_FIELDS = 14;

    /** 画像注入系统提示的最大长度（防止 note 被滥用为提示注入载体，08 §七） */
    int PROMPT_MAX_CHARS = 600;

    /** #53 读取（未填写 → exists=false，不用 404——"还没填"是正常状态） */
    UserPortraitDTO.PortraitVO get(Long userId);

    /** #54 保存（全量提交，UPSERT；并发由唯一索引兜底） */
    void save(Long userId, UserPortraitDTO.SaveRequest request);

    /** #55 清空（物理删除；不级联删除专属对话，后果由前端提示） */
    void delete(Long userId);

    /** #56 字段字典（中文标签唯一来源） */
    UserPortraitDTO.OptionsVO options();

    /**
     * 画像注入文本（专属对话生成用）：person-readable 中文段落，≤600 字。
     *
     * @return 画像为空/未填写时返回 null（调用方据此决定 portraitUsed）
     */
    String promptSection(Long userId);

    /** 画像是否有实质内容（completeness > 0），#58 前置校验用 */
    boolean hasContent(Long userId);
}
