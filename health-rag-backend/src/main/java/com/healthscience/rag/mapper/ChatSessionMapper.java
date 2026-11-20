package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.ChatSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 会话 Mapper。
 */
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 用户的会话列表（按更新时间倒序），含子查询统计的消息条数。
     *
     * <p>用 Map 承载是因为 messageCount 不属于实体字段；service 层再转 VO。
     */
    @Select("""
            SELECT s.id,
                   s.title,
                   s.kind,
                   (SELECT COUNT(*) FROM chat_message m
                     WHERE m.session_id = s.id AND m.deleted = FALSE) AS message_count,
                   s.create_time,
                   s.update_time
              FROM chat_session s
             WHERE s.user_id = #{userId} AND s.deleted = FALSE
               AND s.kind = 'normal'
             ORDER BY s.update_time DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> selectSessionList(@Param("userId") Long userId,
                                                @Param("offset") long offset,
                                                @Param("limit") long limit);

    /**
     * 健康对话列表（M16 v4.0 契约 #58，仅供个人健康科普模块）：
     * kind='health' 按 updateTime 倒序，可多条。
     */
    @Select("""
            SELECT s.id,
                   s.title,
                   s.kind,
                   (SELECT COUNT(*) FROM chat_message m
                     WHERE m.session_id = s.id AND m.deleted = FALSE) AS message_count,
                   s.create_time,
                   s.update_time
              FROM chat_session s
             WHERE s.user_id = #{userId} AND s.deleted = FALSE AND s.kind = 'health'
             ORDER BY s.update_time DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> selectHealthSessionList(@Param("userId") Long userId,
                                                      @Param("offset") long offset,
                                                      @Param("limit") long limit);
}
