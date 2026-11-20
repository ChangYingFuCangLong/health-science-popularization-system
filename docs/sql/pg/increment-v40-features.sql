-- ============================================================
-- v4.0 待实现功能 · 停机窗口增量（B-25/B-26/B-27/B-28/B-29/B-30/B-31 全量）
-- 依据：02-接口文档 v4.0 §十一 总表；设计全文见 07/08/10/11/12 号设计依据
-- 执行前必须 pg_dump 备份；ON_ERROR_STOP 开启。
-- ============================================================

-- ------------------------------------------------------------
-- 1. B-28：会话健康事实（规则抽取产物；随会话逻辑删除失效；⛔ 管理端不可见）
-- ------------------------------------------------------------
CREATE TABLE chat_health_fact (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    metric_key  VARCHAR(30) NOT NULL,             -- weight / systolic / diastolic / glucose /
                                                  -- heart_rate / sleep_hours / steps …
    value       NUMERIC(8,2) NOT NULL,
    unit        VARCHAR(20),
    message_id  BIGINT      NOT NULL,             -- 来源用户消息（可溯源到轮次）
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_health_fact_session ON chat_health_fact (session_id, create_time);

-- ------------------------------------------------------------
-- 2. B-27：对照解答落库列（双答案 = 两条助手消息，同组共享 conflict_group_id）
-- ------------------------------------------------------------
ALTER TABLE chat_message
    ADD COLUMN answer_source     VARCHAR(10) NULL,  -- global / fused；NULL = 普通单答案
    ADD COLUMN conflict_group_id VARCHAR(40) NULL;

-- ------------------------------------------------------------
-- 3. B-29：指标目录（系统 19 项 seed 由 seed 脚本灌入；自定义 ≤10/人）
-- ------------------------------------------------------------
CREATE TABLE metric_def (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key         VARCHAR(30) NOT NULL,             -- 稳定英文码，如 weight / systolic / 自定义 p_xxx
    label       VARCHAR(50) NOT NULL,             -- 中文名
    unit        VARCHAR(20),
    min_value   NUMERIC(10,2),
    max_value   NUMERIC(10,2),
    category    VARCHAR(20) NOT NULL DEFAULT 'basic',  -- basic(标准) / custom(自定义)
    owner_user_id BIGINT,                         -- custom 必有 owner；basic 恒 NULL
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_metric_def_scope CHECK ((category = 'custom') = (owner_user_id IS NOT NULL))
);
CREATE UNIQUE INDEX uk_metric_def_key_active ON metric_def (key) WHERE NOT deleted;
CREATE INDEX idx_metric_def_owner ON metric_def (owner_user_id) WHERE category = 'custom' AND NOT deleted;

-- 19 条标准指标（basic，owner 恒 NULL）——与画像/健康中心既有口径对齐
INSERT INTO metric_def (key, label, unit, min_value, max_value, category) VALUES
    ('weight',      '体重',       'kg',     20,    300,   'basic'),
    ('systolic',    '收缩压',     'mmHg',   60,    260,   'basic'),
    ('diastolic',   '舒张压',     'mmHg',   40,    200,   'basic'),
    ('heart_rate',  '心率',       'bpm',    30,    220,   'basic'),
    ('sleep_hours', '睡眠时长',   'h',      0,     24,    'basic'),
    ('steps',       '步数',       '步',     0,     100000,'basic'),
    ('glucose',     '空腹血糖',   'mmol/L', 1,     40,    'basic'),
    ('body_temp',   '体温',       '℃',     33,    43,    'basic'),
    ('bmi',         'BMI',        '',       10,    80,    'basic'),
    ('height',      '身高',       'cm',     80,    250,   'basic'),
    ('waist',       '腰围',       'cm',     30,    200,   'basic'),
    ('blood_oxygen','血氧饱和度', '%',      50,    100,   'basic'),
    ('hba1c',       '糖化血红蛋白','%',     3,     20,    'basic'),
    ('ldl',         '低密度脂蛋白','mmol/L', 0,     20,    'basic'),
    ('hdl',         '高密度脂蛋白','mmol/L', 0,     10,    'basic'),
    ('triglyceride','甘油三酯',   'mmol/L', 0,     40,    'basic'),
    ('uric_acid',   '尿酸',       'μmol/L', 50,    1200,  'basic'),
    ('calorie_in',  '摄入热量',   'kcal',   0,     10000, 'basic'),
    ('exercise_min','运动时长',   'min',    0,     600,   'basic');

-- B-29：health_metric.type 放开为目录引用 + 记录来源
ALTER TABLE health_metric
    ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'manual';
ALTER TABLE health_metric
    DROP CONSTRAINT IF EXISTS health_metric_type_check;

-- ------------------------------------------------------------
-- 4. B-30：健康备忘 KV（自由健康信息 + 报告抽取文本项；≤50 条/人）
-- ------------------------------------------------------------
CREATE TABLE health_kv (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    key         VARCHAR(30)  NOT NULL,           -- 引用指标目录 key（含自定义）
    label       VARCHAR(50),                     -- 写入时的中文标签快照
    value       VARCHAR(300) NOT NULL,           -- 文本值（如"去年体检查出轻度脂肪肝"）
    source      VARCHAR(10)  NOT NULL DEFAULT 'manual' CHECK (source IN ('manual','report')),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_health_kv_user_key ON health_kv (user_id, key);
COMMENT ON TABLE health_kv IS '健康备忘 KV（自由文本健康信息；一人一 key 覆盖；≤50 条/人由应用层校验）';

-- ------------------------------------------------------------
-- 5. B-31：文档解惑
-- ------------------------------------------------------------
ALTER TABLE chat_session
    DROP CONSTRAINT chk_session_kind;
ALTER TABLE chat_session
    ADD CONSTRAINT chk_session_kind CHECK (kind IN ('normal', 'health', 'file'));

ALTER TABLE kb_document
    DROP CONSTRAINT chk_kb_doc_scope;
ALTER TABLE kb_document
    ADD CONSTRAINT chk_kb_doc_scope CHECK (scope IN ('global', 'personal', 'file'));

-- 会话-文件集（多对多；一个解惑会话 ≤10 份文件）
CREATE TABLE qa_session_document (
    id          BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  BIGINT   NOT NULL,
    document_id BIGINT   NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qa_session_doc UNIQUE (session_id, document_id)
);
CREATE INDEX idx_qa_doc_session ON qa_session_document (document_id);
