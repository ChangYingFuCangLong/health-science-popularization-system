package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.ChatHealthFact;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 会话健康事实 Mapper。
 */
public interface ChatHealthFactMapper extends BaseMapper<ChatHealthFact> {

    /** 某会话的全部健康事实（新→旧），注入用；恒 JOIN 会话未删除状态（随会话删而失效） */
    @Select("""
            SELECT f.metric_key AS metricKey, f.value, f.unit
              FROM chat_health_fact f
              JOIN chat_session s ON s.id = f.session_id AND s.deleted = FALSE
             WHERE f.session_id = #{sessionId}
             ORDER BY f.create_time DESC
             LIMIT 20
            """)
    List<Map<String, Object>> selectBySession(@Param("sessionId") Long sessionId);
}
