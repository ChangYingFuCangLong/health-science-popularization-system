package com.healthscience.rag.service;

import java.nio.file.Path;

/**
 * 知识库导入能力（M5 引入，M9.5/B-10 起向量内嵌 PostgreSQL，ADR-023）。
 *
 * <p>数据源为上游 Python 流水线产出的分块 JSONL（已清洗、已分块），
 * 本服务负责：落库 + 向量化（同一行分块携带自己的向量，杜绝"有块无向量"的静默失败）。
 * 向量化最终失败的分块仍落库（embedding 为 NULL），由管理端"重建索引"回填。
 */
public interface KbImportService {

    /**
     * 从目录导入全部 {@code *.jsonl}。
     *
     * @param dir     分块文件目录
     * @param rebuild 是否先清空既有知识库（全量重建）
     */
    ImportResult importFromDir(Path dir, boolean rebuild);

    /**
     * @param pendingCount 向量化最终失败、以 NULL 向量入库的分块数（管理端重建可补）
     */
    record ImportResult(
            int docCount,
            int chunkCount,
            int embeddedCount,
            int pendingCount,
            long costMs,
            int dimension
    ) {
        @Override
        public String toString() {
            return String.format("文档 %d 篇 / 分块 %d 条（已向量化 %d，待回填 %d）/ 维度 %d / 耗时 %.1fs",
                    docCount, chunkCount, embeddedCount, pendingCount, dimension, costMs / 1000.0);
        }
    }
}
