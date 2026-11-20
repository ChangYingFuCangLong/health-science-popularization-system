-- ============================================================
-- 健康科普 RAG 系统 · PostgreSQL 增量脚本 v4.0（终态合并版）
-- 版本：v4.0（2026-10-25）｜ 依据：docs/V2/02-接口文档.md §十一 DDL 总表
--       + 07/08/10/11/12 号设计依据（个人知识库/健康画像与个人解惑/指标目录化/
--         模块总归一/文档解惑）
-- 表数量：9 张 → 15 张（+user_portrait / chat_health_fact / health_kv /
--         metric_def / user_kb_setting / qa_session_document）
--
-- ⚠️ 终态合并说明：本文件把 07→08→10→11→12 五段"接力 ALTER"（后段 DROP 前段
--    刚建的约束再重建）合并为**一次到位的终态 DDL**——库在执行前从未应用过任何
--    一段，五段接力的中间态没有存在意义。终态口径：
--      · kb_document.scope 三值（global/personal/file），owner 成对 CHECK 覆盖后两值
--      · chat_session.kind 三值（normal/health/file）
--      · health_metric.type 引用 metric_def 目录 key（VARCHAR(40)，无枚举 CHECK）
--
-- 对 10 号设计稿的两处修正（实施时发现，已回写设计文档口径）：
--   ① uk_metric_def_owner_key 必须用 NULLS NOT DISTINCT（PG15+）——普通 UNIQUE
--      视 NULL 互异，"(NULL, key)" 系统行约束失效，seed 幂等（ON CONFLICT）也随之失效；
--   ② health_metric 除 DROP type 四值 CHECK 外，还须 DROP chk_health_metric_value2
--      （bp↔value2 强制配对）——目录化后自定义双值指标（has_value2=true）合法，
--      value2 校验按目录在应用层执行。
--
-- 幂等：全部语句可重复执行（IF NOT EXISTS / IF EXISTS / DO 块吞 duplicate_object /
--       seed ON CONFLICT DO NOTHING）。
-- 执行：docker exec -i health-rag-postgres psql -U postgres -d health_rag -v \
--       ON_ERROR_STOP=1 -f - < 本文件 ｜ 执行前先 pg_dump 备份
-- ============================================================

-- ------------------------------------------------------------
-- 1. 用户健康画像（08 §3.1：单用户单条，物理删除，与 health_metric 同口径）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_portrait (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    -- 基本信息
    gender      VARCHAR(10)  CHECK (gender IS NULL OR gender IN ('male', 'female', 'other')),
    birth_year  SMALLINT     CHECK (birth_year IS NULL OR birth_year BETWEEN 1900 AND 2100),
    height_cm   SMALLINT     CHECK (height_cm IS NULL OR height_cm BETWEEN 80 AND 250),
    weight_kg   NUMERIC(5,2) CHECK (weight_kg IS NULL OR weight_kg BETWEEN 20 AND 300),
    -- 健康状况
    conditions  JSONB,       -- 慢病标签数组，如 ["hypertension","hyperlipidemia"]
    allergies   VARCHAR(300),
    medications VARCHAR(500),
    -- 生活方式
    exercise    VARCHAR(20), -- never | rare | weekly1_2 | weekly3_5 | daily
    sleep_hours NUMERIC(3,1) CHECK (sleep_hours IS NULL OR sleep_hours BETWEEN 0 AND 24),
    smoking     VARCHAR(20), -- never | quit | light | heavy
    drinking    VARCHAR(20), -- never | rarely | social | frequent
    diet        JSONB,       -- 饮食偏好标签数组，如 ["low_salt","vegetarian"]
    -- 目标与补充
    goals       JSONB,       -- 目标标签数组，如 ["lose_weight","control_bp"]
    note        VARCHAR(1000),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 一人一条：应用层 UPSERT，库级唯一兜底并发（物理删除无 deleted，全表 UNIQUE 即可）
CREATE UNIQUE INDEX IF NOT EXISTS uk_portrait_user ON user_portrait (user_id);
COMMENT ON TABLE user_portrait IS '用户健康画像（单条，物理删除）';

-- ------------------------------------------------------------
-- 2. 会话健康事实（08 §3.1：规则抽取产物，追加型）
--    随会话逻辑删除而失效（查询恒 JOIN 会话未删除状态）；
--    ⛔ 管理端不可见（与画像同一隐私口径，不进日志/trace）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_health_fact (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    metric_key  VARCHAR(30)  NOT NULL,             -- weight / systolic / diastolic / …
    value       NUMERIC(8,2) NOT NULL,
    unit        VARCHAR(20),                       -- kg / mmHg / mmol/L / h / 步 …
    message_id  BIGINT       NOT NULL,             -- 来源用户消息（可溯源到轮次）
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_health_fact_session ON chat_health_fact (session_id, create_time);
COMMENT ON TABLE chat_health_fact IS '会话健康事实（用户口头指标，规则抽取，随账号物理删）';

-- ------------------------------------------------------------
-- 3. 健康备忘 KV（11 §四：信息型文本，与数值型指标分工；物理删除）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS health_kv (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    item_key    VARCHAR(50)  NOT NULL,              -- 如「过敏史」；重复 key 允许
    item_value  VARCHAR(500) NOT NULL,
    source      VARCHAR(10)  NOT NULL DEFAULT 'manual'
                CHECK (source IN ('manual', 'report')),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_health_kv_user ON health_kv (user_id, create_time DESC);
COMMENT ON TABLE health_kv IS '健康备忘 KV（信息型文本，物理删除）';

-- ------------------------------------------------------------
-- 4. 指标定义目录（10 §二：注册表——系统标准 owner=NULL + 用户自定义）
--    修正①：NULLS NOT DISTINCT 使 (NULL, key) 系统行同样受唯一约束
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS metric_def (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id BIGINT,                             -- NULL = 系统标准；非 NULL = 用户私有
    metric_key    VARCHAR(40)  NOT NULL,              -- 标准 key 或 'c{id}'（插入后回填）
    label         VARCHAR(30)  NOT NULL,              -- 显示名（中文）
    unit          VARCHAR(20),                        -- kg / mmHg / mmol/L / 分 …
    category      VARCHAR(20)  NOT NULL DEFAULT 'custom',
    value_min     NUMERIC(8,2),                       -- 可选；NULL = 不校验
    value_max     NUMERIC(8,2),
    has_value2    BOOLEAN      NOT NULL DEFAULT FALSE,
    value2_label  VARCHAR(30),                        -- 如「舒张压」
    portrait_sync BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE, -- 自定义删除=逻辑删，记录只读保留
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_metric_def_owner_key
    ON metric_def (owner_user_id, metric_key) NULLS NOT DISTINCT WHERE NOT deleted;
COMMENT ON TABLE metric_def IS '指标定义目录（系统标准 + 用户自定义，B-29）';

-- ------------------------------------------------------------
-- 5. 个人资料库偏好（07 #53：唯一开关 includeInChat）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_kb_setting (
    user_id         BIGINT    PRIMARY KEY,
    include_in_chat BOOLEAN   NOT NULL DEFAULT TRUE,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 收敛修正：清除废弃版残留（default_scope 属已否决的 kbScope 三态设计；07 #53 仅 includeInChat 唯一开关）
ALTER TABLE user_kb_setting DROP COLUMN IF EXISTS default_scope;
ALTER TABLE user_kb_setting ADD COLUMN IF NOT EXISTS create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
COMMENT ON TABLE user_kb_setting IS '个人资料库偏好（一人一行，UPSERT）';

-- ------------------------------------------------------------
-- 6. 文档解惑：会话-文件集多对多（12 §四：复合主键即防重复关联约束）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qa_session_document (
    session_id  BIGINT   NOT NULL,
    document_id BIGINT   NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, document_id)
);
CREATE INDEX IF NOT EXISTS idx_qasd_document ON qa_session_document (document_id);
COMMENT ON TABLE qa_session_document IS '文档解惑会话-文件集（多对多）';

-- ------------------------------------------------------------
-- 7. kb_document：归属三列终态（scope 三值 + owner 成对 CHECK）
-- ------------------------------------------------------------
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS scope VARCHAR(10) NOT NULL DEFAULT 'global';
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;
DO $$ BEGIN
    ALTER TABLE kb_document ADD CONSTRAINT chk_kb_doc_scope
        CHECK (scope IN ('global', 'personal', 'file'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
-- ⚠️ 收敛式约束（2026-10-25 实测教训）：同名约束可能来自另一会话先应用的**旧版两值定义**，
--    按名跳过会把旧定义留在库里。统一改为「先删同名、再建终态」，重跑必然收敛到本文件口径。
ALTER TABLE kb_document DROP CONSTRAINT IF EXISTS chk_kb_doc_scope;
ALTER TABLE kb_document ADD CONSTRAINT chk_kb_doc_scope
    CHECK (scope IN ('global', 'personal', 'file'));
ALTER TABLE kb_document DROP CONSTRAINT IF EXISTS chk_kb_doc_scope_owner;
ALTER TABLE kb_document ADD CONSTRAINT chk_kb_doc_scope_owner
    CHECK ((scope IN ('personal', 'file')) = (owner_user_id IS NOT NULL));
CREATE INDEX IF NOT EXISTS idx_kb_doc_owner ON kb_document (owner_user_id, create_time DESC)
    WHERE owner_user_id IS NOT NULL AND deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_kb_doc_scope ON kb_document (scope, create_time DESC)
    WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 8. chat_session：kind 终态三值（08 §3.1 + 12 §四合并；
--    原 uk_session_portrait_active 每用户一条设计已在 08 五次修订作废，从未落库）
-- ------------------------------------------------------------
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'normal';
ALTER TABLE chat_session DROP CONSTRAINT IF EXISTS chk_session_kind;
ALTER TABLE chat_session ADD CONSTRAINT chk_session_kind
    CHECK (kind IN ('normal', 'health', 'file'));
CREATE INDEX IF NOT EXISTS idx_session_kind_update ON chat_session (user_id, kind, update_time DESC)
    WHERE NOT deleted;

-- ------------------------------------------------------------
-- 9. chat_message：对照解答双答案落库（07 §四）
-- ------------------------------------------------------------
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS answer_source VARCHAR(10)
    CHECK (answer_source IS NULL OR answer_source IN ('global', 'fused'));
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS conflict_group_id VARCHAR(40);
CREATE INDEX IF NOT EXISTS idx_message_conflict_group ON chat_message (conflict_group_id)
    WHERE conflict_group_id IS NOT NULL;

-- ------------------------------------------------------------
-- 10. health_metric：目录化适配（10 §二）+ 溯源列（11 §四）
--     修正②：value2 配对 CHECK 一并移除——自定义双值指标（has_value2=true）合法，
--     范围/配对校验按 metric_def 目录在应用层执行（3001 语义不变）
-- ------------------------------------------------------------
ALTER TABLE health_metric ALTER COLUMN type TYPE VARCHAR(40);
ALTER TABLE health_metric DROP CONSTRAINT IF EXISTS chk_health_metric_type;      -- 设计稿口径
ALTER TABLE health_metric DROP CONSTRAINT IF EXISTS health_metric_type_check;    -- 01-schema 内联 CHECK 的实际名
ALTER TABLE health_metric DROP CONSTRAINT IF EXISTS chk_health_metric_value2;    -- 修正②（设计稿遗漏）
ALTER TABLE health_metric ADD COLUMN IF NOT EXISTS source VARCHAR(10) NOT NULL DEFAULT 'manual';
DO $$ BEGIN
    ALTER TABLE health_metric ADD CONSTRAINT chk_health_metric_source
        CHECK (source IN ('manual', 'report'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ------------------------------------------------------------
-- 11. seed：18 项标准指标目录（10 §1.1 全量枚举；幂等）
--     ★ = portrait_sync，默认维持原 4 项（weight/bp/sleep/steps），与 08 §2.3 兼容
--     注意：bp 的 value_min/max 为收缩压 60-260；舒张压 40-200 的范围由应用层
--     按 has_value2 语义执行（metric_def 无 value2 范围列，10 号未定义，不擅自加列）
-- ------------------------------------------------------------
INSERT INTO metric_def (owner_user_id, metric_key, label, unit, category, value_min, value_max, has_value2, value2_label, portrait_sync) VALUES
(NULL, 'weight',            '体重',       'kg',     '体征',     20,    300,    FALSE, NULL,      TRUE),
(NULL, 'height',            '身高',       'cm',     '体征',     80,    250,    FALSE, NULL,      FALSE),
(NULL, 'waist',             '腰围',       'cm',     '体征',     40,    200,    FALSE, NULL,      FALSE),
(NULL, 'body_fat',          '体脂率',     '%',      '体征',     3,     60,     FALSE, NULL,      FALSE),
(NULL, 'temperature',       '体温',       '℃',     '体征',     35,    43,     FALSE, NULL,      FALSE),
(NULL, 'bp',                '血压',       'mmHg',   '血压心肺', 60,    260,    TRUE,  '舒张压',  TRUE),
(NULL, 'heart_rate',        '心率',       'bpm',    '血压心肺', 30,    220,    FALSE, NULL,      FALSE),
(NULL, 'spo2',              '血氧饱和度', '%',      '血压心肺', 70,    100,    FALSE, NULL,      FALSE),
(NULL, 'fasting_glucose',   '空腹血糖',   'mmol/L', '代谢',     1.0,   33.3,   FALSE, NULL,      FALSE),
(NULL, 'postprandial_glucose','餐后2h血糖','mmol/L','代谢',     1.0,   33.3,   FALSE, NULL,      FALSE),
(NULL, 'hba1c',             '糖化血红蛋白','%',     '代谢',     3,     20,     FALSE, NULL,      FALSE),
(NULL, 'uric_acid',         '血尿酸',     'μmol/L', '代谢',     90,    1000,   FALSE, NULL,      FALSE),
(NULL, 'total_cholesterol', '总胆固醇',   'mmol/L', '代谢',     1,     15,     FALSE, NULL,      FALSE),
(NULL, 'ldl_chol',          '低密度脂蛋白','mmol/L','代谢',     0.5,   10,     FALSE, NULL,      FALSE),
(NULL, 'triglyceride',      '甘油三酯',   'mmol/L', '代谢',     0.1,   20,     FALSE, NULL,      FALSE),
(NULL, 'sleep',             '睡眠时长',   'h',      '睡眠运动', 0,     24,     FALSE, NULL,      TRUE),
(NULL, 'steps',             '步数',       '步',     '睡眠运动', 0,     100000, FALSE, NULL,      TRUE),
(NULL, 'water',             '摄水量',     'ml',     '睡眠运动', 0,     10000,  FALSE, NULL,      FALSE)
ON CONFLICT (owner_user_id, metric_key) WHERE NOT deleted DO NOTHING;

-- ============================================================
-- 执行后校验（02 §十一 + 09 阶段1 四条）：
--   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public';  -- 15
--   SELECT COUNT(DISTINCT scope) FROM kb_document;                               -- 1（全 global）
--   SELECT COUNT(DISTINCT kind) FROM chat_session;                               -- 1（全 normal）
--   SELECT COUNT(*) FROM metric_def WHERE owner_user_id IS NULL;                 -- 18
--   SELECT COUNT(*) FROM health_metric WHERE source <> 'manual';                 -- 0
--   SELECT COUNT(*) FROM health_metric m WHERE NOT EXISTS
--     (SELECT 1 FROM metric_def d WHERE d.metric_key = m.type AND NOT d.deleted); -- 0（悬空引用巡检）
-- ============================================================
