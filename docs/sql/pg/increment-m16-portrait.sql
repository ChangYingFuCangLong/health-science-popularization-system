-- ============================================================
-- 用户健康画像与专属对话 · 增量（M16）
-- 依据：docs/V2/08-待实现接口-用户健康画像与专属对话.md §3.1
-- 前置：01-schema.sql 已执行；已有库直接执行本段（执行前 pg_dump 备份）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 健康画像（单用户单条）
--    约定：物理删除，无 deleted 审计列——与 health_metric 同一裁决口径
--    （用户自助健康数据，删除即消失，符合隐私预期）
-- ------------------------------------------------------------
CREATE TABLE user_portrait (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,

    -- 基本信息
    gender      VARCHAR(10)  CHECK (gender IS NULL OR gender IN ('male', 'female', 'other')),
    birth_year  SMALLINT     CHECK (birth_year IS NULL OR birth_year BETWEEN 1900 AND 2100),
    height_cm   SMALLINT     CHECK (height_cm IS NULL OR height_cm BETWEEN 80 AND 250),
    weight_kg   NUMERIC(5,2) CHECK (weight_kg IS NULL OR weight_kg BETWEEN 20 AND 300),

    -- 健康状况
    conditions  JSONB,       -- 慢病标签数组
    allergies   VARCHAR(300),
    medications VARCHAR(500),

    -- 生活方式
    exercise    VARCHAR(20), -- never | rare | weekly1_2 | weekly3_5 | daily
    sleep_hours NUMERIC(3,1) CHECK (sleep_hours IS NULL OR sleep_hours BETWEEN 0 AND 24),
    smoking     VARCHAR(20), -- never | quit | light | heavy
    drinking    VARCHAR(20), -- never | rarely | social | frequent
    diet        JSONB,       -- 饮食偏好标签数组

    -- 目标与补充
    goals       JSONB,       -- 目标标签数组
    note        VARCHAR(1000),

    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 一人一条：应用层 UPSERT，库级约束兜底并发
CREATE UNIQUE INDEX uk_portrait_user ON user_portrait (user_id);
COMMENT ON TABLE user_portrait IS '用户健康画像（单条，物理删除）';
-- 2026-10-25 v4.0 校正：kind 取值 'health'（原 'portrait' 作废）；2009 作废

-- ------------------------------------------------------------
-- 2. 会话表增量：会话类型
-- ------------------------------------------------------------
ALTER TABLE chat_session
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'normal';

ALTER TABLE chat_session
    ADD CONSTRAINT chk_session_kind CHECK (kind IN ('normal', 'portrait'));

-- 每个用户至多一条未删除的专属对话（并发创建由库约束兜底，应用层转 2009）
-- deleted 是逻辑删除：用户删掉专属对话后该行不再占位，可重新创建
CREATE UNIQUE INDEX uk_session_portrait_active ON chat_session (user_id)
    WHERE kind = 'portrait' AND NOT deleted;

-- 专属对话置顶排序的支撑索引
CREATE INDEX idx_session_kind_update ON chat_session (user_id, kind, update_time DESC)
    WHERE NOT deleted;
