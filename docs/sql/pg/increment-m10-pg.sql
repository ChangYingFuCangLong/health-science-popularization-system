-- ============================================================
-- 健康科普 RAG 系统 · M10 增量 DDL（PostgreSQL）
-- 任务卡 D-3 修订项补齐 ｜ 2026-09-19
-- 背景：现网库建表早于 D-3 修订，chat_message 缺 B-14/B-23 消费的两列。
-- 已在现网执行（幂等），本文件是留档以便复现。
-- 执行库：health_rag（docker exec health-rag-postgres psql -U postgres -d health_rag -f ...）
-- ============================================================

ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS suggestions_json JSONB;
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS trace_json      JSONB;

COMMENT ON COLUMN chat_message.suggestions_json IS '追问建议缓存 JSON（04-裁决 §4.3），首次生成后写入';
COMMENT ON COLUMN chat_message.trace_json       IS '检索过程回放 JSON（B-23 检索回放接口，M14 消费）';
