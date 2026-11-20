-- ============================================================
-- 健康科普 RAG 系统 · PostgreSQL 扩展与中文文本搜索配置
-- 版本：v2.0（2026-09-19）｜ 依据：docs/V2/04-架构师裁决与融合设计方案.md §一 Q1/Q2
-- 前置：镜像内已编译安装 pgvector（apt）与 zhparser（源码钉版，见 deploy/pg/）
-- 执行：仅由 postgres 官方镜像 initdb 机制自动执行（数据卷为空的首次启动），
--       文件名 00- 前缀保证排在 01-schema.sql / 02-seed.sql 之前
-- ============================================================

-- 向量类型与 HNSW/IVF 索引（pgvector）
CREATE EXTENSION IF NOT EXISTS vector;

-- 中文分词解析器（SCWS 词库分词）
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 中文文本搜索配置：只保留实义词性（n 名词 / v 动词 / a 形容词 / i 成语 / e 感叹 / l 习语），
-- 丢弃标点、数字串、助词等噪声 token。全库检索统一写 'chinese' 配置名。
-- 用法示例：
--   to_tsvector('chinese', content)            -- 建向量（schema 中由生成列自动维护）
--   websearch_to_tsquery('chinese', :qtext)    -- 查询词解析（用户友好语法）
CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION chinese
    ADD MAPPING FOR n, v, a, i, e, l WITH simple;

-- 自检（initdb 后手工验证分词是否词级切分）：
--   SELECT to_tsvector('chinese', '高血压患者的饮食注意事项');
--   期望切出 高血压/患者/饮食/注意 之类的词级 token，而非整句成串。
--
-- 健康领域自定义词典（如需补充「褪黑素」「幽门螺杆菌」等词）：
--   镜像内放置 dict 文件后执行 zhparser.zhparser_multi_dict 配置，见 deploy/README-pg.md §六。
