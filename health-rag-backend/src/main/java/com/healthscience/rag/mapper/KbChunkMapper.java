package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.ai.model.KeywordHit;
import com.healthscience.rag.ai.model.VectorHit;
import com.healthscience.rag.entity.KbChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 文档分块 Mapper（ADR-023：向量与全文索引内嵌本表）。
 *
 * <p><b>向量路</b>：HNSW（{@code vector_cosine_ops}）余弦距离 {@code <=>}，
 * 查询向量以 1024 维文本（{@code '[0.1,0.2,...]'}）传入并显式 CAST。
 * <p><b>关键词路</b>：{@code tsv} 生成列（zhparser 中文分词）+ GIN，
 * 查询侧用同一 {@code chinese} 配置分词后 OR 拼接，
 * {@code ts_rank_cd} 排序 + LIMIT 控噪。
 * <b>勿</b>把中文整句直接喂 {@code websearch_to_tsquery}：无空格段被当作单一词项、
 * 展开成 {@code <->} 短语链，自然语言整句恒 0 召回（DEF-1，2026-09-19 修复）。
 *
 * <p><b>scope 归属过滤（M15，07 §四）</b>：全局库（owner IS NULL）对所有登录用户可见，
 * 个人库仅 owner 可见。三条批量 SQL（clearEmbeddings / selectPendingEmbedding /
 * selectVectorStats）的 scope 过滤<b>必须成对同口径</b>——由调用方显式传 scope 参数，
 * 让"成对"在编译期无法写错（07 §2.3 灾难 B）。
 */
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /** 全量重建用：逻辑删除全局库全部分块（个人库不受影响，07 §2.3 灾难 A） */
    @Update("""
            UPDATE kb_chunk SET deleted = TRUE
             WHERE deleted = FALSE
               AND document_id IN (SELECT id FROM kb_document WHERE scope = 'global')
            """)
    int logicDeleteAll();

    /**
     * 全量重建用：<b>物理删除</b>全局库全部分块（F2，与 {@code KbDocumentMapper#purgeGlobalDocs()} 配套）。
     *
     * <p>必须在删除文档<b>之前</b>执行：这里按 document_id 子查询定位，文档一旦删掉就无块可删，
     * 会留下孤儿分块（表间无外键约束，数据库不会替你兜底）。
     */
    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM kb_chunk
             WHERE document_id IN (SELECT id FROM kb_document WHERE scope = 'global')
            """)
    int purgeGlobalChunks();

    /** scope 过滤片段（三态语义由构造保证，07 §3.3）：ownerUserId 传 NULL 时 'all' 退化为仅全局 */
    String SCOPE_FILTER = """
            (
              #{scopeFilter} = 'all'     AND (d.owner_user_id IS NULL OR d.owner_user_id = CAST(#{ownerUserId} AS bigint))
              OR #{scopeFilter} = 'global'   AND d.owner_user_id IS NULL
              OR #{scopeFilter} = 'personal' AND d.owner_user_id = CAST(#{ownerUserId} AS bigint)
            )
            """;

    // 末尾必须带换行符：文本块拼接时保证 END 与 AS scope 不粘连（实测 ENDAS 事故）
    String SCOPE_EXPR = "CASE WHEN d.owner_user_id IS NULL THEN 'global' ELSE 'personal' END\n";

    /**
     * 向量召回（03 附录 B ①）：余弦相似度 = 1 - 距离。
     *
     * @param qvec        查询向量文本，形如 {@code [0.021,-0.113,...]}（1024 维）
     * @param category    可选分类过滤（NULL 不过滤）
     * @param scopeFilter all / global / personal（07 §3.3）
     * @param ownerUserId 当前用户（personal/all 语义需要；可为 null → 退化为仅全局）
     */
    @Select("""
            SELECT c.id, c.document_id, c.content, c.section,
                   d.title, d.source, d.category,
                   """ + SCOPE_EXPR + """
            AS scope,
                   1 - (c.embedding <=> CAST(#{qvec} AS vector)) AS score
              FROM kb_chunk c
              JOIN kb_document d ON d.id = c.document_id AND d.deleted = FALSE
             WHERE c.deleted = FALSE
               AND c.embedding IS NOT NULL
               AND (CAST(#{category} AS text) IS NULL OR d.category = #{category})
               AND """ + SCOPE_FILTER + """
             ORDER BY c.embedding <=> CAST(#{qvec} AS vector)
             LIMIT #{limit}
            """)
    List<VectorHit> selectByVector(@Param("qvec") String qvec,
                                   @Param("category") String category,
                                   @Param("scopeFilter") String scopeFilter,
                                   @Param("ownerUserId") Long ownerUserId,
                                   @Param("limit") int limit);

    /**
     * 关键词召回（DEF-1）：zhparser 词级分词 + OR 拼接 + ts_rank_cd 相关性排序。
     * M15：补 JOIN kb_document 并施加 scope 过滤（关键词路是主要泄漏面，07 §四 #2）。
     */
    @Select("""
            WITH q AS (
              SELECT to_tsquery('chinese', string_agg(quote_literal(lex), ' | ')) AS tsq
                FROM unnest(tsvector_to_array(to_tsvector('chinese', #{qtext}))) AS lex
               WHERE length(lex) >= 2
                  OR lex NOT IN ('是','的','了','在','和','与','及','或','并','而','但',
                                 '不','没','有','要','会','能','可','该','当','很','都',
                                 '也','就','还','被','把','对','为','从','向','按','据',
                                 '因','由','吗','呢','吧','啊','请','什么','怎么','怎样',
                                 '如何','哪些','多少','可以','应该','需要','如果','因为',
                                 '所以','但是','不过','以及','还是','为什么')
            )
            SELECT c.id, c.document_id, c.content, c.section,
                   """ + SCOPE_EXPR + """
            AS scope,
                   ts_rank_cd(c.tsv, q.tsq) AS score
              FROM kb_chunk c
              JOIN kb_document d ON d.id = c.document_id AND d.deleted = FALSE
              , q
             WHERE c.deleted = FALSE
               AND q.tsq IS NOT NULL
               AND c.tsv @@ q.tsq
               AND """ + SCOPE_FILTER + """
             ORDER BY score DESC
             LIMIT #{limit}
            """)
    List<KeywordHit> selectByKeyword(@Param("qtext") String qtext,
                                     @Param("scopeFilter") String scopeFilter,
                                     @Param("ownerUserId") Long ownerUserId,
                                     @Param("limit") int limit);

    /**
     * 就绪状态真值查询（03 §D4）：<b>限定 scope</b>（M15）——
     * 全站可用性判断只看全局库，个人库 pending 不得拉挂 isReady()（07 §2.3 灾难 B）。
     */
    @Select("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE embedding IS NULL) AS pending
              FROM kb_chunk
             WHERE deleted = FALSE
               AND document_id IN (SELECT id FROM kb_document WHERE scope = #{scope} AND deleted = FALSE)
            """)
    Map<String, Object> selectVectorStats(@Param("scope") String scope);

    /**
     * 个人库分块统计（07 §5.3 #44）：owner 维度 total/pending。
     */
    @Select("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE c.embedding IS NULL) AS pending
              FROM kb_chunk c
              JOIN kb_document d ON d.id = c.document_id AND d.deleted = FALSE
             WHERE c.deleted = FALSE AND d.owner_user_id = #{userId}
            """)
    Map<String, Object> selectOwnerChunkStats(@Param("userId") Long userId);

    /**
     * 取一批待向量化分块（游标分页：id > afterId，保证失败跳过后循环必然前进）。
     *
     * @param documentId 指定文档（单文档重建，scope 语义随之明确，传 scope=null）
     * @param scope      documentId 为 null 时必传（全量重建只清全局库，与 clearEmbeddings 成对）
     */
    @Select("""
            <script>
            SELECT id, document_id, content
              FROM kb_chunk
             WHERE deleted = FALSE AND embedding IS NULL AND id &gt; #{afterId}
            <if test="documentId != null"> AND document_id = #{documentId} </if>
            <if test="documentId == null and scope != null"> AND document_id IN (SELECT id FROM kb_document WHERE scope = #{scope} AND deleted = FALSE) </if>
             ORDER BY id
             LIMIT #{limit}
            </script>
            """)
    List<KbChunk> selectPendingEmbedding(@Param("documentId") Long documentId,
                                         @Param("scope") String scope,
                                         @Param("afterId") long afterId,
                                         @Param("limit") int limit);

    /**
     * 重建前作废旧向量（换模型重建语义）：documentId 指定文档，或 scope='global' 全局库。
     * <b>与 selectPendingEmbedding 成对同口径</b>（07 §2.3 灾难 B）。
     */
    @Update("""
            <script>
            UPDATE kb_chunk SET embedding = NULL, update_time = CURRENT_TIMESTAMP
             WHERE deleted = FALSE
            <if test="documentId != null"> AND document_id = #{documentId} </if>
            <if test="documentId == null and scope != null"> AND document_id IN (SELECT id FROM kb_document WHERE scope = #{scope} AND deleted = FALSE) </if>
            </script>
            """)
    int clearEmbeddings(@Param("documentId") Long documentId, @Param("scope") String scope);

    /** 回填单个分块的向量（1024 维文本，显式 CAST 由服务端解析） */
    @Update("""
            UPDATE kb_chunk
               SET embedding = CAST(#{embedding} AS vector),
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{id}
            """)
    int updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    /**
     * 相关文档推荐（M12/B-21，契约 02 §5.4）：目标文档全部 chunk embedding 的均值做质心，
     * 近邻检索并排除本文档，按文档聚合取最大相似度。
     * M15：限全局库——用户私人文档不得出现在「相关文章」推荐里（07 §四 #8）。
     */
    @Select("""
            WITH target AS (
              SELECT AVG(embedding) AS c
                FROM kb_chunk
               WHERE document_id = #{docId} AND deleted = FALSE AND embedding IS NOT NULL
            )
            SELECT d.id, d.title, d.category,
                   MAX(1 - (c.embedding <=> t.c)) AS similarity
              FROM kb_chunk c
              JOIN kb_document d ON d.id = c.document_id AND d.deleted = FALSE
              CROSS JOIN target t
             WHERE c.deleted = FALSE AND c.embedding IS NOT NULL
               AND c.document_id <> #{docId} AND t.c IS NOT NULL
               AND d.owner_user_id IS NULL
             GROUP BY d.id, d.title, d.category
             ORDER BY similarity DESC
             LIMIT #{limit}
            """)
    java.util.List<java.util.Map<String, Object>> selectRelatedDocuments(@Param("docId") Long docId,
                                                                         @Param("limit") int limit);

    /**
     * 文档解惑·向量召回（B-31，契约 12 号）：检索硬限定在该会话的文件集
     * （d.id IN 文件集），永不参与任何合并检索；走子查询避免 IN 空集语法错误。
     */
    @Select("""
            SELECT c.id, c.document_id, c.content, c.section,
                   d.title, d.source, d.category,
                   'file' AS scope,
                   1 - (c.embedding <=> CAST(#{qvec} AS vector)) AS score
              FROM kb_chunk c
              JOIN kb_document d ON d.id = c.document_id AND d.deleted = FALSE
             WHERE c.deleted = FALSE
               AND c.embedding IS NOT NULL
               AND c.document_id IN (SELECT document_id FROM qa_session_document WHERE session_id = #{sessionId})
             ORDER BY c.embedding <=> CAST(#{qvec} AS vector)
             LIMIT #{limit}
            """)
    List<VectorHit> selectByVectorInSession(@Param("qvec") String qvec,
                                            @Param("sessionId") Long sessionId,
                                            @Param("limit") int limit);

    /**
     * 文档解惑·关键词召回（B-31）：同上文件集硬限定。
     */
    @Select("""
            WITH q AS (
              SELECT to_tsquery('chinese', string_agg(quote_literal(lex), ' | ')) AS tsq
                FROM unnest(tsvector_to_array(to_tsvector('chinese', #{qtext}))) AS lex
               WHERE length(lex) >= 2 LIMIT 12)
            SELECT c.id, c.document_id, c.content, c.section,
                   'file' AS scope,
                   ts_rank_cd(c.tsv, q.tsq) AS score
              FROM kb_chunk c
              JOIN kb_document d ON d.id = c.document_id AND d.deleted = FALSE
              , q
             WHERE c.deleted = FALSE AND q.tsq IS NOT NULL AND c.tsv @@ q.tsq
               AND c.document_id IN (SELECT document_id FROM qa_session_document WHERE session_id = #{sessionId})
             ORDER BY score DESC
             LIMIT #{limit}
            """)
    List<KeywordHit> selectByKeywordInSession(@Param("qtext") String qtext,
                                              @Param("sessionId") Long sessionId,
                                              @Param("limit") int limit);

    /**
     * 块间余弦相似度（B-27 冲突判定）：用户库 top 块 vs 基础库 top 块。
     * 双档阈值（07 §2.2）：≥0.90 高度相似 / 0.75~0.90 同题不同表述 → 双答案。
     */
    @Select("""
            SELECT 1 - (a.embedding <=> b.embedding)
              FROM kb_chunk a, kb_chunk b
             WHERE a.id = #{userChunkId} AND b.id = #{baseChunkId}
            """)
    Double selectChunkSimilarity(@Param("userChunkId") Long userChunkId,
                                 @Param("baseChunkId") Long baseChunkId);

    /** D9 删除级联：分块逻辑删除后向量一并置 NULL，释放 pgvector 存储 */
    @Update("UPDATE kb_chunk SET embedding = NULL WHERE document_id = #{documentId}")
    int nullEmbeddingByDocument(@Param("documentId") Long documentId);
}
