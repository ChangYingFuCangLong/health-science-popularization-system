package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.ChatMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 消息 Mapper。
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 取会话最近 N 条消息（按时间正序返回），用于组装对话历史 */
    default List<ChatMessage> selectRecent(Long sessionId, int limit) {
        // 先取最新 limit 条（倒序），再在内存里反转成正序
        List<ChatMessage> latest = selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT " + limit));
        java.util.Collections.reverse(latest);
        return latest;
    }

    /** 删除会话下全部消息（逻辑删除，随会话删除联动） */
    @Update("UPDATE chat_message SET deleted = TRUE WHERE session_id = #{sessionId} AND deleted = FALSE")
    int deleteBySessionId(@Param("sessionId") Long sessionId);
}
