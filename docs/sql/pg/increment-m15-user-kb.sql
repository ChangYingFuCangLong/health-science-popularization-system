-- ============================================================
-- 用户个人知识库 · 增量（M15）
-- 依据：docs/V2/07-待实现接口-用户个人知识库.md §3.1
-- 前置：01-schema.sql 已执行。本文不修改 01-schema.sql（重置脚本）：
--       新库由本增量补齐；已有库直接执行本段。
-- 执行前必须 pg_dump 备份（09 §七 阶段 1）。
-- ============================================================

ALTER TABLE kb_document
    ADD COLUMN scope         VARCHAR(10) NOT NULL DEFAULT 'global',
    ADD COLUMN owner_user_id BIGINT;

ALTER TABLE kb_document
    ADD CONSTRAINT chk_kb_doc_scope
        CHECK (scope IN ('global', 'personal'));

-- 归属完整性：personal 必有 owner，global 必无 owner（库级兜底，应用层另有校验）
ALTER TABLE kb_document
    ADD CONSTRAINT chk_kb_doc_scope_owner
        CHECK ((scope = 'personal') = (owner_user_id IS NOT NULL));

-- 个人库列表查询（我的资料按时间倒序）
CREATE INDEX idx_kb_doc_owner ON kb_document (owner_user_id, create_time DESC)
    WHERE owner_user_id IS NOT NULL AND deleted = FALSE;

-- 管理端全局库查询（scope 过滤为主路径）
CREATE INDEX idx_kb_doc_scope ON kb_document (scope, create_time DESC) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 个人库偏好设置（07 §5.3 #51/#52，决策 D5：独立表）
-- ------------------------------------------------------------
CREATE TABLE user_kb_setting (
    user_id         BIGINT      PRIMARY KEY,
    include_in_chat BOOLEAN     NOT NULL DEFAULT TRUE,   -- 个人资料是否参与问答检索
    default_scope   VARCHAR(10) NOT NULL DEFAULT 'all'
                    CHECK (default_scope IN ('all', 'global', 'personal')),
    update_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 偏好默认值语义：无行 = 参与问答 / 默认 all（服务端按缺省处理，无需为每个用户插行）
