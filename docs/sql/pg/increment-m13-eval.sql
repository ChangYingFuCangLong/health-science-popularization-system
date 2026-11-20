-- ============================================================
-- B1 评测闭环（M13）：score_source 列（AUTO=LLM-judge / MANUAL=人工复核）
-- 执行前 pg_dump 备份。
-- ============================================================
ALTER TABLE eval_result ADD COLUMN IF NOT EXISTS score_source VARCHAR(10);
COMMENT ON COLUMN eval_result.score_source IS '评分来源：AUTO=LLM-as-judge / MANUAL=人工复核；NULL=未评分';
