package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.QaSessionDocument;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 文档解惑会话-文件集 Mapper。
 */
public interface QaSessionDocumentMapper extends BaseMapper<QaSessionDocument> {

    /** 会话文件列表（含处理状态，供 #72 与概览汇总） */
    @Select("""
            SELECT d.id, d.title, d.file_type AS fileType, d.file_size AS fileSize,
                   d.status, d.chunk_count AS chunkCount, d.fail_reason AS failReason,
                   d.create_time AS createTime
              FROM qa_session_document q
              JOIN kb_document d ON d.id = q.document_id AND d.deleted = FALSE
             WHERE q.session_id = #{sessionId}
             ORDER BY q.create_time
            """)
    List<Map<String, Object>> selectSessionFiles(@Param("sessionId") Long sessionId);
}
