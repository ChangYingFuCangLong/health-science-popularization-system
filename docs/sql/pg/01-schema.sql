-- ============================================================
-- 健康科普 RAG 系统 · PostgreSQL 建表脚本 v2.0（M9.5 实施版）
-- 依据：docs/V2/03-数据库PostgreSQL迁移设计方案.md 附录 A（已批准）
--       + docs/V2/04-架构师裁决与融合设计方案.md §二 S1/S2、§三 health_metric
-- 前置：00-extensions.sql 已执行（vector / zhparser / chinese 文本搜索配置）
-- 数据库：PostgreSQL 16 ｜ UTF8 ｜ 时区 Asia/Shanghai（容器 TZ）
-- 表数量：15 张 = 9 张核心表（kb_vector_meta 退役、向量与全文索引并入 kb_chunk、
--              health_metric 健康档案）+ 6 张用户资产表（v4.0 增量，02 §十一 DDL 总表：
--              user_portrait / chat_health_fact / health_kv / metric_def /
--              user_kb_setting / qa_session_document）
-- 统一审计字段：create_time / update_time / deleted（BOOLEAN 逻辑删除）
--              物理删除（无 deleted 列）的用户自助表：health_metric（04 §三）与
--              v4.0 的 user_portrait / chat_health_fact / health_kv /
--              qa_session_document / user_kb_setting（健康数据隐私预期 + 偏好/关联表）
-- 约定：不建物理外键（03 §5.4-D9，引用完整靠应用层 + 删除级联约定）
-- ⛔ 本文件是「重置脚本」不是「迁移脚本」：含 DROP。已有数据的库走增量 ALTER；
--   确需重置先 pg_dump 备份并确认备份可用（见 deploy/README-pg.md §五）
-- ============================================================

-- ------------------------------------------------------------
-- 1. 用户表
--    关键变更：唯一约束改「部分唯一索引」（只约束活跃行），
--    MySQL 版「删除时改写 username=CONCAT(LEFT(username,26),'_del_',id)」协议整体退役（D1）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id                   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username             VARCHAR(50)  NOT NULL,
    password             VARCHAR(100) NOT NULL,                    -- BCrypt 密文
    nickname             VARCHAR(50),
    email                VARCHAR(100),
    avatar               VARCHAR(255),
    role                 VARCHAR(20)  NOT NULL DEFAULT 'USER'
                         CHECK (role IN ('USER', 'ADMIN')),
    status               SMALLINT     NOT NULL DEFAULT 1
                         CHECK (status IN (0, 1)),                -- 0 禁用 1 正常
    last_login_at        TIMESTAMP,
    token_invalid_before TIMESTAMP,                                -- 早于该时刻签发的 token 一律拒绝（登出/改密/禁用踢下线，04 §S5）
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE
);
-- 只约束活跃行：删除用户无需改名、同名可再注册、同名删两次不再撞约束
CREATE UNIQUE INDEX uk_username_active ON sys_user (username) WHERE NOT deleted;
COMMENT ON TABLE sys_user IS '用户表';

-- ------------------------------------------------------------
-- 2. 知识库文档表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS kb_document;
CREATE TABLE kb_document (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    source      VARCHAR(200),                              -- 机构 / 网址 / 出处
    category    VARCHAR(50),                               -- 营养/运动/心理/睡眠/慢病 ...
    file_type   VARCHAR(20)  NOT NULL,                     -- pdf/docx/md/txt/html
    file_path   VARCHAR(500) NOT NULL,
    file_size   BIGINT       NOT NULL DEFAULT 0,
    status      SMALLINT     NOT NULL DEFAULT 0
                CHECK (status IN (0, 1, 2, 3)),            -- 0待处理 1解析中 2完成 3失败
    fail_reason VARCHAR(500),
    chunk_count INT          NOT NULL DEFAULT 0,
    upload_by   BIGINT       NOT NULL,                     -- 上传人 sys_user.id
    scope       VARCHAR(10)  NOT NULL DEFAULT 'global'
                CHECK (scope IN ('global', 'personal', 'file')),  -- 权威库/个人资料库/文档解惑
    owner_user_id BIGINT,                                  -- scope≠global 时必填（上传人）
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 归属成对约束（07/12 号）：personal/file 必须有 owner，global 必须 NULL
    CONSTRAINT chk_kb_doc_scope_owner
        CHECK ((scope IN ('personal', 'file')) = (owner_user_id IS NOT NULL))
);
CREATE INDEX idx_kb_doc_status      ON kb_document (status);
CREATE INDEX idx_kb_doc_category    ON kb_document (category);
CREATE INDEX idx_kb_doc_create_time ON kb_document (create_time);
CREATE INDEX idx_kb_doc_owner       ON kb_document (owner_user_id, create_time DESC)
                                    WHERE owner_user_id IS NOT NULL AND deleted = FALSE;
CREATE INDEX idx_kb_doc_scope       ON kb_document (scope, create_time DESC) WHERE deleted = FALSE;
COMMENT ON TABLE kb_document IS '知识库文档表';

-- ------------------------------------------------------------
-- 3. 文档分块表（核心改造表）
--    向量与中文全文索引内嵌：分块与向量化同事务提交，一致性由库保证（P1 根治）
--    变更：-embedding_id；+embedding VECTOR(1024)；+tsv 生成列；+section（04 §三/03 §3.3）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS kb_chunk;
CREATE TABLE kb_chunk (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id BIGINT       NOT NULL,
    chunk_index INT          NOT NULL,                     -- 同文档内从 0 开始
    section     VARCHAR(255),                              -- 章节路径（结构感知分块产出，引用溯源）
    content     TEXT         NOT NULL,
    char_count  INT          NOT NULL DEFAULT 0,
    embedding   VECTOR(1024),                              -- bge-m3；NULL=未向量化；维度由列类型强制
    meta_json   JSONB,                                     -- 章节层级 / 适用人群等
    tsv         TSVECTOR GENERATED ALWAYS AS
                (to_tsvector('chinese', content)) STORED,  -- 生成列：永不与 content 失同步（D3）
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_chunk_doc_idx_active ON kb_chunk (document_id, chunk_index) WHERE NOT deleted;
CREATE INDEX idx_chunk_document ON kb_chunk (document_id);
CREATE INDEX idx_chunk_tsv      ON kb_chunk USING GIN (tsv);
CREATE INDEX idx_chunk_embed    ON kb_chunk USING HNSW (embedding vector_cosine_ops)
                                WITH (m = 16, ef_construction = 64);
COMMENT ON TABLE kb_chunk IS '文档分块表（向量与全文索引内嵌）';

-- ------------------------------------------------------------
-- 4. 会话表（update_time 由消息表触发器维护，见第 5 节末尾）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS chat_session;
CREATE TABLE chat_session (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    title       VARCHAR(100) NOT NULL DEFAULT '新对话',   -- 取首条提问前 20 字
    kind        VARCHAR(20)  NOT NULL DEFAULT 'normal'
                CHECK (kind IN ('normal', 'health', 'file')),  -- 通用解惑/个人解惑/文档解惑
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_session_user        ON chat_session (user_id);
CREATE INDEX idx_session_update      ON chat_session (update_time DESC);
CREATE INDEX idx_session_kind_update ON chat_session (user_id, kind, update_time DESC)
                                     WHERE NOT deleted;   -- #58 个人解惑列表支撑
COMMENT ON TABLE chat_session IS '问答会话表';

-- ------------------------------------------------------------
-- 5. 消息表
--    变更：+query_text（落库改写后 query，实验分析用）；references_json→JSONB；
--          is_rejected→BOOLEAN；会话定位索引改 (session_id, create_time) 复合；
--          +suggestions_json / +trace_json（M10 增量，2026-09-19 并入——
--          increment-m10-pg.sql 仅服务本列缺失的已建库，新库由本文件直接建出）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS chat_message;
CREATE TABLE chat_message (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id      BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT        NOT NULL,
    query_text      VARCHAR(1000),                          -- 实际用于检索的改写后 query
    references_json JSONB,                                  -- [{chunkId,docId,doc,section,digest,score}]
    suggestions_json JSONB,                                 -- 追问建议缓存（04 §4.3），首次生成后写入
    trace_json      JSONB,                                  -- 检索过程回放（M14 检索回放接口消费）
    answer_source   VARCHAR(10)                             -- 对照解答：global 系统版 / fused 融合版（07 §2.4）
                    CHECK (answer_source IS NULL OR answer_source IN ('global', 'fused')),
    conflict_group_id VARCHAR(40),                         -- 同组双答案共享 UUID，整组重跑的关联键
    retrieve_mode   VARCHAR(30)
                    CHECK (retrieve_mode IS NULL OR
                           retrieve_mode IN ('none', 'naive', 'hybrid', 'hybrid_rerank')),
                    -- 'none' = 无检索基线臂（04 §S1，与 02-接口文档 §4.6/§7.3 契约对齐）
    token_in        INT         NOT NULL DEFAULT 0,
    token_out       INT         NOT NULL DEFAULT 0,
    latency_ms      INT         NOT NULL DEFAULT 0,
    first_token_ms  INT         NOT NULL DEFAULT 0,         -- 首字延迟（NFR「首字≤3s」指标源）
    finish_status   SMALLINT    NOT NULL DEFAULT 1
                    CHECK (finish_status IN (0, 1)),        -- 0 流中断(内容保留) 1 完成
    is_rejected     BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_message_session_time ON chat_message (session_id, create_time);
CREATE INDEX idx_message_user         ON chat_message (user_id);
CREATE INDEX idx_message_create_time  ON chat_message (create_time);
CREATE INDEX idx_message_conflict_group ON chat_message (conflict_group_id)
                                        WHERE conflict_group_id IS NOT NULL;
-- 不为 finish_status 建索引：区分度低（绝大多数为 1）、查询先按 session_id 定位，负收益
COMMENT ON TABLE chat_message IS '问答消息表';

-- 会话活跃时间由数据库保证（替代 v1.1「业务层记得 touch」的隐式契约，D5）：
-- 插入消息或流结束回写内容时，自动前移所属会话的 update_time，
-- 会话列表「按最近活跃排序」从此永远正确。
CREATE OR REPLACE FUNCTION touch_chat_session() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE chat_session SET update_time = CURRENT_TIMESTAMP
     WHERE id = NEW.session_id;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_message_touch_session
    AFTER INSERT OR UPDATE OF content, finish_status ON chat_message
    FOR EACH ROW EXECUTE FUNCTION touch_chat_session();

-- ------------------------------------------------------------
-- 6. 消息反馈表
--    变更：补 (message_id, user_id) 部分唯一索引 = 一人一评（04 §Q4）；
--          应用层提交语义为 UPSERT：INSERT ... ON CONFLICT ... DO UPDATE（改评覆盖，04 §S4）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS message_feedback;
CREATE TABLE message_feedback (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(20)  NOT NULL
                CHECK (type IN ('useful', 'useless', 'wrong')),
    comment     VARCHAR(500),                              -- 补充说明
    status      SMALLINT     NOT NULL DEFAULT 0
                CHECK (status IN (0, 1)),                  -- 0 待处理 1 已处理
    handle_by   BIGINT,                                    -- 处理人 sys_user.id
    handle_time TIMESTAMP,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_feedback_msg_user_active ON message_feedback (message_id, user_id) WHERE NOT deleted;
CREATE INDEX idx_feedback_message ON message_feedback (message_id);
CREATE INDEX idx_feedback_status  ON message_feedback (status);
COMMENT ON TABLE message_feedback IS '答案反馈表';

-- ------------------------------------------------------------
-- 7. 评测集表（论文实验用）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS eval_dataset;
CREATE TABLE eval_dataset (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question      VARCHAR(500) NOT NULL,
    ground_truth  TEXT,
    ref_chunk_ids JSONB,                                   -- 参考片段 ID 数组，如 [17,19]
    category      VARCHAR(50),
    difficulty    SMALLINT     NOT NULL DEFAULT 2
                  CHECK (difficulty IN (1, 2, 3)),         -- 1易 2中 3难
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_eval_ds_category ON eval_dataset (category);
COMMENT ON TABLE eval_dataset IS '评测集（问答对）';

-- ------------------------------------------------------------
-- 8. 消融实验结果表
--    变更：strategy CHECK 纳入 'none'（无检索基线臂，04 §S1）；
--          faithfulness 口径统一为 LLM-as-judge 1~5 分（04 §S2）；
--          citation_correct 改 0/1 单条判定（比率语义汇总时再算，04 §S2）；
--          补 (batch_no, dataset_id, strategy) 部分唯一索引——重跑灌数不可能污染实验
-- ------------------------------------------------------------
DROP TABLE IF EXISTS eval_result;
CREATE TABLE eval_result (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_no         VARCHAR(40)   NOT NULL,
    dataset_id       BIGINT        NOT NULL,
    strategy         VARCHAR(30)   NOT NULL
                     CHECK (strategy IN ('none', 'naive', 'hybrid', 'hybrid_rerank')),
    retrieved_json   JSONB,                                -- [{chunkId,rank,score}]
    hit_rank         INT,                                  -- 首个命中片段排名，未命中 NULL
    is_hit           BOOLEAN       NOT NULL DEFAULT FALSE,
    answer           TEXT,
    faithfulness     NUMERIC(3,2)  CHECK (faithfulness IS NULL OR
                                          faithfulness BETWEEN 1 AND 5),  -- LLM-as-judge 1~5 分
    citation_correct SMALLINT      NOT NULL DEFAULT 0
                     CHECK (citation_correct IN (0, 1)),   -- 单条引用命中判定 0/1
    latency_ms       INT           NOT NULL DEFAULT 0,
    create_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_eval_batch_qs_active ON eval_result (batch_no, dataset_id, strategy) WHERE NOT deleted;
CREATE INDEX idx_eval_ds_strategy ON eval_result (dataset_id, strategy);
CREATE INDEX idx_eval_batch       ON eval_result (batch_no);
COMMENT ON TABLE eval_result IS '消融实验结果表';

-- ------------------------------------------------------------
-- 9. 健康指标表（前端 v2「健康中心」云端存储 · P0，04 §三）
--    约定：物理删除（用户自助数据，删除即消失，符合健康数据隐私预期），
--    因此无 deleted 审计列——全库唯一例外，论文「数据库设计」章需注明
-- ------------------------------------------------------------
CREATE TABLE health_metric (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    type        VARCHAR(40)   NOT NULL,          -- 引用 metric_def 目录 key（标准 key 或 'c{id}' 自定义）
    value       NUMERIC(8,2)  NOT NULL,          -- 主值：体重kg / 收缩压 / 睡眠小时 / 步数 …
    value2      NUMERIC(8,2),                    -- 仅双值指标（has_value2=true，如血压舒张压）
    source      VARCHAR(10)   NOT NULL DEFAULT 'manual'
                CHECK (source IN ('manual', 'report')),   -- 手动录入 / 报告抽取（11 §四）
    recorded_at TIMESTAMP     NOT NULL,          -- 用户自填或默认当时
    note        VARCHAR(200),
    create_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
    -- 范围/双值校验（3001）由应用层按 metric_def 目录定义执行（10 号 §1.3）；
    -- 原 type 四值 CHECK 与 bp↔value2 配对 CHECK 已随目录化移除——自定义双值指标合法
);
CREATE INDEX idx_health_metric_user ON health_metric (user_id, type, recorded_at);
COMMENT ON TABLE health_metric IS '健康指标表（目录化 type，物理删除）';

-- ------------------------------------------------------------
-- 10. 用户健康画像（08 §3.1：单用户单条，物理删除）
-- ------------------------------------------------------------
CREATE TABLE user_portrait (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    gender      VARCHAR(10)  CHECK (gender IS NULL OR gender IN ('male', 'female', 'other')),
    birth_year  SMALLINT     CHECK (birth_year IS NULL OR birth_year BETWEEN 1900 AND 2100),
    height_cm   SMALLINT     CHECK (height_cm IS NULL OR height_cm BETWEEN 80 AND 250),
    weight_kg   NUMERIC(5,2) CHECK (weight_kg IS NULL OR weight_kg BETWEEN 20 AND 300),
    conditions  JSONB,                             -- 慢病标签数组
    allergies   VARCHAR(300),
    medications VARCHAR(500),
    exercise    VARCHAR(20),                       -- never | rare | weekly1_2 | weekly3_5 | daily
    sleep_hours NUMERIC(3,1) CHECK (sleep_hours IS NULL OR sleep_hours BETWEEN 0 AND 24),
    smoking     VARCHAR(20),                       -- never | quit | light | heavy
    drinking    VARCHAR(20),                       -- never | rarely | social | frequent
    diet        JSONB,                             -- 饮食偏好标签数组
    goals       JSONB,                             -- 目标标签数组
    note        VARCHAR(1000),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_portrait_user ON user_portrait (user_id);   -- 一人一条，UPSERT
COMMENT ON TABLE user_portrait IS '用户健康画像（单条，物理删除）';

-- ------------------------------------------------------------
-- 11. 会话健康事实（08 §3.1：规则抽取产物，追加型，随会话逻辑删除而失效）
--     ⛔ 管理端不可见（与画像同一隐私口径）
-- ------------------------------------------------------------
CREATE TABLE chat_health_fact (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    metric_key  VARCHAR(30)  NOT NULL,             -- weight / systolic / diastolic / …
    value       NUMERIC(8,2) NOT NULL,
    unit        VARCHAR(20),
    message_id  BIGINT       NOT NULL,             -- 来源用户消息（溯源到轮次）
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_health_fact_session ON chat_health_fact (session_id, create_time);
COMMENT ON TABLE chat_health_fact IS '会话健康事实（用户口头指标，规则抽取）';

-- ------------------------------------------------------------
-- 12. 健康备忘 KV（11 §四：信息型文本，与数值型指标分工；物理删除）
-- ------------------------------------------------------------
CREATE TABLE health_kv (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    item_key    VARCHAR(50)  NOT NULL,             -- 如「过敏史」；重复 key 允许
    item_value  VARCHAR(500) NOT NULL,
    source      VARCHAR(10)  NOT NULL DEFAULT 'manual'
                CHECK (source IN ('manual', 'report')),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_health_kv_user ON health_kv (user_id, create_time DESC);
COMMENT ON TABLE health_kv IS '健康备忘 KV（信息型文本，物理删除）';

-- ------------------------------------------------------------
-- 13. 指标定义目录（10 §二：注册表——系统标准 owner=NULL + 用户自定义）
--     NULLS NOT DISTINCT：(NULL, key) 系统行同样受唯一约束，seed 幂等依赖此语义
-- ------------------------------------------------------------
CREATE TABLE metric_def (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id BIGINT,                            -- NULL = 系统标准；非 NULL = 用户私有
    metric_key    VARCHAR(40)  NOT NULL,            -- 标准 key 或 'c{id}'（插入后回填）
    label         VARCHAR(30)  NOT NULL,
    unit          VARCHAR(20),
    category      VARCHAR(20)  NOT NULL DEFAULT 'custom',
    value_min     NUMERIC(8,2),                      -- 可选；NULL = 不校验
    value_max     NUMERIC(8,2),
    has_value2    BOOLEAN      NOT NULL DEFAULT FALSE,
    value2_label  VARCHAR(30),
    portrait_sync BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE, -- 自定义删除=逻辑删，记录只读保留
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_metric_def_owner_key
    ON metric_def (owner_user_id, metric_key) NULLS NOT DISTINCT WHERE NOT deleted;
COMMENT ON TABLE metric_def IS '指标定义目录（系统标准 + 用户自定义）';

-- ------------------------------------------------------------
-- 14. 个人资料库偏好（07 #53：唯一开关）
-- ------------------------------------------------------------
CREATE TABLE user_kb_setting (
    user_id         BIGINT    PRIMARY KEY,
    include_in_chat BOOLEAN   NOT NULL DEFAULT TRUE,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE user_kb_setting IS '个人资料库偏好（一人一行，UPSERT）';

-- ------------------------------------------------------------
-- 15. 文档解惑：会话-文件集多对多（12 §四：复合主键防重复关联）
-- ------------------------------------------------------------
CREATE TABLE qa_session_document (
    session_id  BIGINT   NOT NULL,
    document_id BIGINT   NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, document_id)
);
CREATE INDEX idx_qasd_document ON qa_session_document (document_id);
COMMENT ON TABLE qa_session_document IS '文档解惑会话-文件集（多对多）';

-- ============================================================
-- 检索 SQL 示例（应用层两个落点，RRF 融合仍在 Java 侧，见 03 §5.4-D10）：
--
-- ① 向量召回（支持元数据过滤——旧 SimpleVectorStore 架构做不到）：
--   SELECT c.id, c.content, d.title AS doc,
--          1 - (c.embedding <=> :qvec::vector) AS score
--     FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id AND NOT d.deleted
--    WHERE NOT c.deleted
--      AND (:category::text IS NULL OR d.category = :category)
--    ORDER BY c.embedding <=> :qvec::vector LIMIT 20;
--
-- ② 关键词召回（DEF-1 修复，2026-09-19：整句禁止直接喂 websearch_to_tsquery——
--    无空格段被当作单一词项按 <-> 短语展开，自然语言整句恒 0 召回。
--    正确姿势：同款 'chinese' 配置分词后 OR 拼接，ts_rank_cd 排序 + LIMIT 控噪。
--    停用词清单以 KbChunkMapper.selectByKeyword 为单一来源，勿在此处各自漂移）：
--   WITH q AS (
--     SELECT to_tsquery('chinese', string_agg(quote_literal(lex), ' | ')) AS tsq
--       FROM unnest(tsvector_to_array(to_tsvector('chinese', :qtext))) AS lex
--      WHERE length(lex) >= 2 OR lex NOT IN ('是','的','了', … /* 见 Mapper */)
--   )
--   SELECT c.id, c.content, ts_rank_cd(c.tsv, q.tsq) AS score
--     FROM kb_chunk c, q
--    WHERE NOT c.deleted AND c.tsv @@ q.tsq
--    ORDER BY score DESC LIMIT 20;
--
-- ③ 向量「就绪」状态真值查询（替代 kb_vector_meta，D4；重建进度 = 1 - pending/total）：
--   SELECT COUNT(*) AS total,
--          COUNT(*) FILTER (WHERE embedding IS NULL) AS pending
--     FROM kb_chunk WHERE NOT deleted;
-- ============================================================
