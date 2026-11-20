package com.healthscience.rag.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 管理端的聚合与联表查询（接口文档 §6 / §7）。
 *
 * <p>这些查询跨多张表、含子查询，不适合用 LambdaQueryWrapper 拼装，
 * 集中放在这里以便统一维护 SQL。方言：PostgreSQL（ADR-023）——
 * 布尔列比较用 {@code deleted = FALSE}，布尔均值用 {@code AVG(CASE WHEN ...)}，
 * 空值兜底用 {@code COALESCE}。
 */
public interface AdminMapper {

    /** §6.8 看板总览：一次查询拿齐总量与均值，避免前端发多个请求 */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM sys_user WHERE deleted = FALSE)                     AS "totalUsers",
              (SELECT COUNT(*) FROM chat_session WHERE deleted = FALSE)                 AS "totalSessions",
              (SELECT COUNT(*) FROM chat_message WHERE deleted = FALSE)                 AS "totalMessages",
              (SELECT COUNT(*) FROM chat_message WHERE deleted = FALSE
                 AND create_time >= CURRENT_DATE)                                       AS "todayMessages",
              (SELECT COALESCE(ROUND(AVG(CASE WHEN is_rejected THEN 1 ELSE 0 END), 4), 0)
                 FROM chat_message WHERE deleted = FALSE AND role = 'assistant')        AS "rejectRate",
              (SELECT COALESCE(ROUND(AVG(latency_ms), 0), 0)
                 FROM chat_message WHERE deleted = FALSE AND role = 'assistant')        AS "avgLatencyMs",
              (SELECT COALESCE(ROUND(AVG(first_token_ms), 0), 0)
                 FROM chat_message WHERE deleted = FALSE AND role = 'assistant')        AS "avgFirstTokenMs",
              (SELECT COUNT(*) FROM kb_document WHERE deleted = FALSE AND scope = 'global') AS "docCount",
              (SELECT COUNT(*) FROM kb_chunk WHERE deleted = FALSE
                 AND document_id IN (SELECT id FROM kb_document WHERE scope = 'global'))  AS "chunkCount"
            """)
    Map<String, Object> selectOverview();

    /** §6.8 近 7 日问答量 */
    @Select("""
            SELECT create_time::date AS date, COUNT(*) AS count
              FROM chat_message
             WHERE deleted = FALSE
               AND create_time >= CURRENT_DATE - INTERVAL '6 days'
             GROUP BY create_time::date
             ORDER BY date
            """)
    List<Map<String, Object>> selectTrend();

    /**
     * §6.8 热门问题：按完全相同的提问聚合。
     */
    @Select("""
            SELECT content AS question, COUNT(*) AS count
              FROM chat_message
             WHERE deleted = FALSE AND role = 'user'
             GROUP BY content
             ORDER BY count DESC, question
             LIMIT #{limit}
            """)
    List<Map<String, Object>> selectHotQuestions(@Param("limit") int limit);

    /**
     * §6.9 问答日志：以助手回答为主体，用子查询回溯它对应的提问。
     * 一条日志 = 一组问答，比分别列出提问和回答更贴近管理端的查看习惯。
     */
    @Select("""
            <script>
            SELECT m.id AS "messageId", m.session_id AS "sessionId", m.user_id AS "userId",
                   u.username AS username,
                   (SELECT q.content FROM chat_message q
                     WHERE q.session_id = m.session_id AND q.id &lt; m.id
                       AND q.role = 'user' AND q.deleted = FALSE
                     ORDER BY q.id DESC LIMIT 1) AS question,
                   m.content AS answer, m.query_text AS "queryText",
                   m.retrieve_mode AS "retrieveMode",
                   m.latency_ms AS "latencyMs", m.is_rejected AS "isRejected",
                   m.finish_status AS "finishStatus", m.create_time AS "createTime"
              FROM chat_message m
              LEFT JOIN sys_user u ON m.user_id = u.id
             WHERE m.deleted = FALSE AND m.role = 'assistant'
            <if test="userId != null">  AND m.user_id = #{userId} </if>
            <if test="isRejected != null"> AND m.is_rejected = #{isRejected} </if>
            <if test="startDate != null"> AND m.create_time &gt;= #{startDate} </if>
            <if test="endDate != null">   AND m.create_time &lt; #{endDate} </if>
             ORDER BY m.id DESC
            </script>
            """)
    List<Map<String, Object>> selectChatLogs(@Param("userId") Long userId,
                                             @Param("isRejected") Boolean isRejected,
                                             @Param("startDate") String startDate,
                                             @Param("endDate") String endDate);

    /** §6.10 反馈列表：带用户名与被反馈回答的摘要，便于定位问题 */
    @Select("""
            <script>
            SELECT f.id AS id, f.message_id AS "messageId", f.user_id AS "userId",
                   u.username AS username, f.type AS type, f.comment AS comment,
                   f.status AS status, f.create_time AS "createTime",
                   LEFT(m.content, 120) AS "answerDigest"
              FROM message_feedback f
              LEFT JOIN sys_user u ON f.user_id = u.id
              LEFT JOIN chat_message m ON f.message_id = m.id
             WHERE f.deleted = FALSE
            <if test="status != null"> AND f.status = #{status} </if>
             ORDER BY f.id DESC
            </script>
            """)
    List<Map<String, Object>> selectFeedbacks(@Param("status") Integer status);

    /** §7.4 实验结果：联表取回问题文本；retrieved_json 取 text 便于上层解析成数组 */
    @Select("""
            <script>
            SELECT r.id AS id, r.batch_no AS "batchNo", r.dataset_id AS "datasetId",
                   d.question AS question, r.strategy AS strategy,
                   r.hit_rank AS "hitRank", r.is_hit AS "isHit",
                   r.retrieved_json::text AS "retrievedJson",
                   r.answer AS answer,
                   r.faithfulness AS faithfulness, r.citation_correct AS "citationCorrect",
                   r.score_source AS "scoreSource",
                   r.latency_ms AS "latencyMs"
              FROM eval_result r
              LEFT JOIN eval_dataset d ON r.dataset_id = d.id
             WHERE r.deleted = FALSE
            <if test="batchNo != null and batchNo != ''"> AND r.batch_no = #{batchNo} </if>
            <if test="strategy != null and strategy != ''"> AND r.strategy = #{strategy} </if>
             ORDER BY r.dataset_id, r.strategy
            </script>
            """)
    List<Map<String, Object>> selectEvalResults(@Param("batchNo") String batchNo,
                                                @Param("strategy") String strategy);

    /**
     * §7.5 指标汇总：按策略聚合。
     * hitRate5 = 命中数 / 样本数；MRR = 1/rank 的均值（未命中记 0）。
     */
    @Select("""
            SELECT strategy,
                   COUNT(*)                                                          AS "sampleCount",
                   ROUND(SUM(CASE WHEN is_hit THEN 1 ELSE 0 END)::numeric / COUNT(*), 4) AS "hitRate5",
                   ROUND(AVG(COALESCE(1.0 / hit_rank, 0)), 4)                        AS mrr,
                   ROUND(AVG(faithfulness), 4)                                       AS "avgFaithfulness",
                   ROUND(AVG(latency_ms), 0)                                         AS "avgLatencyMs"
              FROM eval_result
             WHERE deleted = FALSE AND batch_no = #{batchNo}
             GROUP BY strategy
             ORDER BY strategy
            """)
    List<Map<String, Object>> selectEvalSummary(@Param("batchNo") String batchNo);
}
