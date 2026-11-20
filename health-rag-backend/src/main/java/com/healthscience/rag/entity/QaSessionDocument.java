package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档解惑会话-文件集（M16 v4.0，B-31，契约 12 号）：多对多绑定。
 *
 * <p>一个解惑会话 ≤10 份文件（应用层校验）；文件本体为 scope='file' 的
 * kb_document/kb_chunk（检索硬限定 {@code d.id IN (文件集)}，永不参与合并检索）。
 */
@Data
@TableName("qa_session_document")
public class QaSessionDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long documentId;

    private LocalDateTime createTime;
}
