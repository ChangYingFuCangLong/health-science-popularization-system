package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.KbDocument;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 知识库文档 Mapper。
 *
 * <p>常用查询（去重、按分类筛选）用 {@code LambdaQueryWrapper} 拼装即可，
 * 仅分类聚合这一处需要自定义 SQL。
 */
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    /** 启动扫描（M12/B-19）：重启卡在「处理中」的僵尸记录重置为失败态 */
    @Update("UPDATE kb_document SET status = 3, fail_reason = '服务重启中断，请重新上传',"
            + " update_time = CURRENT_TIMESTAMP WHERE status = 1 AND deleted = FALSE")
    int resetParsingZombies();

    /** 全量重建用：逻辑删除<b>全局库</b>全部文档（M15 加 scope——个人库不受语料重导影响，07 §2.3 灾难 A） */
    @Update("UPDATE kb_document SET deleted = TRUE WHERE deleted = FALSE AND scope = 'global'")
    int logicDeleteAll();

    /**
     * 全量重建用：<b>物理删除</b>全局库全部文档（F2，测试报告 §27.4）。
     *
     * <p>重建语义就是"清空重来"，逻辑删只会让表无限膨胀 —— 实测累积 1312 条僵尸文档
     * （其中 2026-09-18 一次重建就产生 1279 条）。这些记录永不参与检索，只增加备份与
     * VACUUM 成本，因此重建路径改为物理删除。
     *
     * <p>⚠️ 调用方必须先执行 {@code KbChunkMapper#purgeGlobalChunks()}：分块按
     * {@code document_id IN (SELECT ...)} 定位，文档删掉后就查不到了。
     * 个人库（scope='personal'）不在删除范围，与逻辑删版本口径一致。
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM kb_document WHERE scope = 'global'")
    int purgeGlobalDocs();

    /** §5.1 分类列表：按文档数倒序 */
    @Select("""
            SELECT category AS name, COUNT(*) AS count
              FROM kb_document
             WHERE deleted = FALSE AND category IS NOT NULL AND category <> ''
             GROUP BY category
             ORDER BY count DESC
            """)
    java.util.List<java.util.Map<String, Object>> selectCategoryCounts();
}
