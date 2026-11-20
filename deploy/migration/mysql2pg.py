#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
健康科普 RAG 系统 · 存量数据迁移：MySQL 8（V1，冻结只读）→ PostgreSQL 16（v2.0 主库）
依据：docs/V2/03-数据库PostgreSQL迁移设计方案.md §六（已批准）+ 04 §二 S2 口径换算

特点：
  · MySQL 侧严格只读（绝不写、绝不改结构）；
  · 主键原样保留（OVERRIDING SYSTEM VALUE），迁移后逐表推齐 IDENTITY 序列；
  · 现成向量从 SimpleVectorStore 持久化文件（health-rag-backend/data/vector-store.json）
    按 kb_chunk.embedding_id 直接回填进 kb_chunk.embedding，不重新调用 Embedding API；
  · 口径换算（03 §6.2 / 04 §S2，与 docs/sql/pg/02-seed.sql 同一规则）：
      faithfulness     : v1 的 0~1 比率 → 1 + 4×v（LLM-as-judge 1~5 分制，NULL 保持 NULL）
      citation_correct : v1 的 0~1 比率 → ≥0.70 记 1，否则 0（单条 0/1 判定）
  · 布尔列（deleted/is_rejected/is_hit）0/1 → true/false；
  · 四个 JSON 列逐值 json.loads 校验后才写入 JSONB，非法值置 NULL 并计数；
  · 结束输出两侧逐表 COUNT(*) 比对（不使用估算值——README 教训）。

用法（在能同时连通 MySQL:3306 与 PG:5432 的机器上执行）：
  pip install pymysql psycopg2-binary
  python mysql2pg.py                       # 连接参数自动读 deploy/.env
  python mysql2pg.py --truncate            # 先清空 PG 目标表再迁（可重复执行）
  python mysql2pg.py --dry-run             # 只校验连通性/扩展/结构，不写数据
安全红线：本脚本永远不写、不删、不改 MySQL 侧任何对象；PG 侧仅 --truncate 时清空业务表。
"""

import argparse
import json
import sys
from pathlib import Path

try:
    import pymysql
except ImportError:
    sys.exit("缺少依赖：pip install pymysql")
try:
    import psycopg2
    import psycopg2.extras as pg_extras
except ImportError:
    sys.exit("缺少依赖：pip install psycopg2-binary")

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent

# MySQL 侧可能缺少的 v2 新增列（缺则以 NULL 迁入，不报错）
OPTIONAL_MYSQL_COLS = {
    "sys_user": ["token_invalid_before"],
    "kb_chunk": ["section"],
    "chat_message": ["query_text"],
}

# 目标 PG 表定义：列 → 处理方式
#   plain=原样 | bool=0/1→布尔 | jsonb=校验后写 JSONB | faith/cite=S2 口径换算 | vector=向量回填
TABLES = [
    ("sys_user", [
        ("id", "plain"), ("username", "plain"), ("password", "plain"), ("nickname", "plain"),
        ("email", "plain"), ("avatar", "plain"), ("role", "plain"), ("status", "plain"),
        ("last_login_at", "plain"), ("token_invalid_before", "plain"),
        ("create_time", "plain"), ("update_time", "plain"), ("deleted", "bool"),
    ]),
    ("kb_document", [
        ("id", "plain"), ("title", "plain"), ("source", "plain"), ("category", "plain"),
        ("file_type", "plain"), ("file_path", "plain"), ("file_size", "plain"),
        ("status", "plain"), ("fail_reason", "plain"), ("chunk_count", "plain"),
        ("upload_by", "plain"), ("create_time", "plain"), ("update_time", "plain"),
        ("deleted", "bool"),
    ]),
    ("kb_chunk", [
        ("id", "plain"), ("document_id", "plain"), ("chunk_index", "plain"),
        ("section", "plain"), ("content", "plain"), ("char_count", "plain"),
        ("embedding", "vector"), ("meta_json", "jsonb"),
        ("create_time", "plain"), ("update_time", "plain"), ("deleted", "bool"),
    ]),
    ("chat_session", [
        ("id", "plain"), ("user_id", "plain"), ("title", "plain"),
        ("create_time", "plain"), ("update_time", "plain"), ("deleted", "bool"),
    ]),
    ("chat_message", [
        ("id", "plain"), ("session_id", "plain"), ("user_id", "plain"), ("role", "plain"),
        ("content", "plain"), ("query_text", "plain"), ("references_json", "jsonb"),
        ("retrieve_mode", "plain"), ("token_in", "plain"), ("token_out", "plain"),
        ("latency_ms", "plain"), ("first_token_ms", "plain"), ("finish_status", "plain"),
        ("is_rejected", "bool"), ("create_time", "plain"), ("update_time", "plain"),
        ("deleted", "bool"),
    ]),
    ("message_feedback", [
        ("id", "plain"), ("message_id", "plain"), ("user_id", "plain"), ("type", "plain"),
        ("comment", "plain"), ("status", "plain"), ("handle_by", "plain"),
        ("handle_time", "plain"), ("create_time", "plain"), ("update_time", "plain"),
        ("deleted", "bool"),
    ]),
    ("eval_dataset", [
        ("id", "plain"), ("question", "plain"), ("ground_truth", "plain"),
        ("ref_chunk_ids", "jsonb"), ("category", "plain"), ("difficulty", "plain"),
        ("create_time", "plain"), ("update_time", "plain"), ("deleted", "bool"),
    ]),
    ("eval_result", [
        ("id", "plain"), ("batch_no", "plain"), ("dataset_id", "plain"),
        ("strategy", "plain"), ("retrieved_json", "jsonb"), ("hit_rank", "plain"),
        ("is_hit", "bool"), ("answer", "plain"), ("faithfulness", "faith"),
        ("citation_correct", "cite"), ("latency_ms", "plain"),
        ("create_time", "plain"), ("update_time", "plain"), ("deleted", "bool"),
    ]),
]

ALL_PG_TABLES = [t for t, _ in TABLES] + ["health_metric"]  # health_metric 无存量，仅参与清空/计数


def load_env(path: Path) -> dict:
    env = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                env[k.strip()] = v.strip()
    return env


def mysql_cols(cur, table):
    cur.execute(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema = DATABASE() AND table_name = %s", (table,))
    return {r[0] for r in cur.fetchall()}


def transform(kind, v, stats):
    if kind == "plain":
        return v
    if kind == "bool":
        return None if v is None else bool(int(v))
    if kind == "jsonb":
        if v is None:
            return None
        s = v.decode("utf-8") if isinstance(v, (bytes, bytearray)) else str(v)
        try:
            json.loads(s)
            return s
        except (ValueError, TypeError):
            stats["bad_json"] += 1
            return None
    if kind == "faith":  # 0~1 比率 → 1~5 分制（04 §S2）
        return None if v is None else round(min(5.0, max(1.0, 1 + 4 * float(v))), 2)
    if kind == "cite":   # 0~1 比率 → 0/1 单条判定（阈值 0.70，与 seed 同规则）
        return 0 if v is None else (1 if float(v) >= 0.70 else 0)
    raise ValueError(kind)


def load_vectors(path: Path):
    """SimpleVectorStore JSON → {id: [floats]}。实测格式：以 id 为键的字典，
    值为 {id, text, metadata, embedding}；同时兼容数组与包一层对象的导出格式。"""
    if not path.exists():
        print(f"[WARN] 向量文件不存在：{path} —— kb_chunk.embedding 将全部为 NULL（可备好后重跑）")
        return {}
    print(f"读取向量文件：{path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    items = []
    if isinstance(data, dict):
        inner = data.values()
        for v in inner:
            if isinstance(v, dict):
                items.append(v)                      # 实测：{id: {id,text,metadata,embedding}}
            elif isinstance(v, list):
                items.extend(x for x in v if isinstance(x, dict))  # 兼容 {documents: [...]}
    elif isinstance(data, list):
        items = [x for x in data if isinstance(x, dict)]
    vectors = {}
    for d in items:
        vid = d.get("id") or d.get("documentId")
        emb = d.get("embedding") or d.get("vector")
        if vid is not None and isinstance(emb, list):
            vectors[str(vid)] = emb
    if not vectors:
        print("[WARN] 向量文件解析结果为空——请人工确认 SimpleVectorStore 导出格式")
    else:
        sample = next(iter(vectors.values()))
        print(f"  解析出 {len(vectors)} 条向量，维度样例 = {len(sample)}")
    return vectors


def flush(pg_cur, table, insert_cols, rows):
    cols_sql = ", ".join(insert_cols)
    pg_extras.execute_values(
        pg_cur,
        f"INSERT INTO {table} ({cols_sql}) OVERRIDING SYSTEM VALUE VALUES %s",
        rows, page_size=500,
    )


def migrate_table(my_cur, pg_cur, table, cols_spec, vectors, stats):
    available = mysql_cols(my_cur, table)
    select_parts, insert_cols, template_parts, kinds = [], [], [], []
    for col, kind in cols_spec:
        if col == "embedding":
            # 源列是 embedding_id（v1 指向 SimpleVectorStore 的 ID），落到 PG 的 embedding 向量列
            if "embedding_id" in available:
                select_parts.append("embedding_id")
            else:
                select_parts.append("NULL")
                print(f"  [INFO] MySQL.{table} 无列 embedding_id，跳过向量回填")
            insert_cols.append("embedding")
            template_parts.append("%s::vector")
            kinds.append("vector")
            continue
        if col not in available:
            if col in OPTIONAL_MYSQL_COLS.get(table, []):
                select_parts.append("NULL")
                print(f"  [INFO] MySQL.{table} 无列 {col}（v2 新增），以 NULL 迁入")
            else:
                raise RuntimeError(f"MySQL.{table} 缺少必需列 {col}，请核对源库版本")
        else:
            select_parts.append(f"`{col}`")
        insert_cols.append(col)
        template_parts.append("%s::jsonb" if kind == "jsonb" else "%s")
        kinds.append(kind)

    my_cur.execute(f"SELECT {', '.join(select_parts)} FROM `{table}`")
    rows, n = [], 0
    for r in my_cur.fetchall():
        out = []
        for kind, v in zip(kinds, r):
            if kind == "vector":
                emb = vectors.get(str(v)) if v is not None else None
                if emb is None:
                    out.append(None)
                elif len(emb) != 1024:
                    stats["bad_vector"] += 1
                    out.append(None)
                else:
                    out.append("[" + ",".join(repr(float(x)) for x in emb) + "]")
                    stats["vectors_filled"] += 1
            else:
                out.append(transform(kind, v, stats))
        rows.append(tuple(out))
        n += 1
        if len(rows) >= 500:
            flush(pg_cur, table, insert_cols, rows)
            rows = []
    if rows:
        flush(pg_cur, table, insert_cols, rows)
    # 显式主键插入后推齐 IDENTITY 序列，否则应用层第一条 INSERT 撞主键：
    # 有行 → setval(max, is_called=true)；空表 → setval(1, is_called=false)，下一条从 1 起
    pg_cur.execute(
        f"SELECT setval(pg_get_serial_sequence(%s, 'id'), "
        f"COALESCE((SELECT MAX(id) FROM {table}), 1), "
        f"EXISTS(SELECT 1 FROM {table}))",
        (table,))
    return n


def main():
    ap = argparse.ArgumentParser(description="MySQL → PostgreSQL 存量迁移（M9.5）")
    ap.add_argument("--env", default=str(REPO_ROOT / "deploy" / ".env"))
    ap.add_argument("--mysql-host", default=None)
    ap.add_argument("--mysql-port", type=int, default=None)
    ap.add_argument("--mysql-user", default=None)
    ap.add_argument("--mysql-password", default=None)
    ap.add_argument("--mysql-db", default=None)
    ap.add_argument("--pg-host", default=None)
    ap.add_argument("--pg-port", type=int, default=None)
    ap.add_argument("--pg-user", default=None)
    ap.add_argument("--pg-password", default=None)
    ap.add_argument("--pg-db", default=None)
    ap.add_argument("--vector-json", default=str(REPO_ROOT / "hou" / "data" / "vector-store.json"))
    ap.add_argument("--truncate", action="store_true", help="迁移前清空 PG 业务表（可重复执行）")
    ap.add_argument("--dry-run", action="store_true", help="只校验连通性/扩展/结构，不写数据")
    args = ap.parse_args()

    env = load_env(Path(args.env))
    my_conf = dict(
        host=args.mysql_host or env.get("MYSQL_HOST", "127.0.0.1"),
        port=args.mysql_port or int(env.get("MYSQL_PORT", "3306")),
        user=args.mysql_user or "root",
        password=args.mysql_password or env.get("MYSQL_ROOT_PASSWORD", ""),
        database=args.mysql_db or "health_rag",
        charset="utf8mb4",
    )
    pg_conf = dict(
        host=args.pg_host or env.get("PG_HOST", "127.0.0.1"),
        port=args.pg_port or int(env.get("PG_PORT", "5432")),
        user=args.pg_user or "postgres",
        password=args.pg_password or env.get("PG_PASSWORD", ""),
        dbname=args.pg_db or "health_rag",
    )
    if not my_conf["password"]:
        sys.exit("MySQL 密码为空：请通过 --mysql-password 或 .env 的 MYSQL_ROOT_PASSWORD 提供")
    if not pg_conf["password"]:
        sys.exit("PG 密码为空：请通过 --pg-password 或 .env 的 PG_PASSWORD 提供")

    print("== 连接（MySQL 只读 / PG 可写）==")
    my = pymysql.connect(**my_conf)
    pg = psycopg2.connect(**pg_conf)
    my_cur = my.cursor()
    pg_cur = pg.cursor()

    pg_cur.execute("SELECT extname FROM pg_extension WHERE extname IN ('vector','zhparser') ORDER BY 1")
    exts = {r[0] for r in pg_cur.fetchall()}
    missing = {"vector", "zhparser"} - exts
    if missing:
        sys.exit(f"[FAIL] PG 缺扩展：{sorted(missing)}（已装：{sorted(exts)}）——"
                 f"先按 deploy/README-pg.md 起 PG 库，确认 00-extensions.sql 已执行")

    print("== 目标库状态检查 ==")
    occupied = {}
    for t in ALL_PG_TABLES:
        pg_cur.execute(f"SELECT COUNT(*) FROM {t}")
        occupied[t] = pg_cur.fetchone()[0]
    if any(v > 0 for v in occupied.values()):
        if not args.truncate:
            nonzero = {k: v for k, v in occupied.items() if v}
            sys.exit(f"[FAIL] PG 目标表已有数据（{nonzero}）。确认可清空后加 --truncate 重跑。")
        print("  --truncate：清空 PG 业务表（仅 PG 侧；MySQL 只读不受影响）")
        pg_cur.execute("TRUNCATE " + ", ".join(ALL_PG_TABLES))
    else:
        print("  目标表全部为空，可直接迁移")

    if args.dry_run:
        pg.rollback()
        print("== dry-run 通过：连通性 / 扩展 / 表结构 OK，未写任何数据 ==")
        return

    vectors = load_vectors(Path(args.vector_json))
    stats = {"bad_json": 0, "bad_vector": 0, "vectors_filled": 0}
    print("== 开始迁移 ==")
    for table, cols_spec in TABLES:
        n = migrate_table(my_cur, pg_cur, table, cols_spec, vectors, stats)
        pg.commit()
        print(f"  {table:<18} {n} 行")

    print("== 两侧 COUNT(*) 比对 ==")
    ok = True
    for table, _ in TABLES:
        my_cur.execute(f"SELECT COUNT(*) FROM `{table}`")
        src = my_cur.fetchone()[0]
        pg_cur.execute(f"SELECT COUNT(*) FROM {table}")
        dst = pg_cur.fetchone()[0]
        flag = "OK  " if src == dst else "DIFF"
        ok = ok and src == dst
        print(f"  [{flag}] {table:<18} mysql={src:<6} pg={dst}")
    pg_cur.execute("SELECT COUNT(*) FROM kb_chunk WHERE embedding IS NOT NULL")
    print(f"  PG.kb_chunk 已带向量行数 = {pg_cur.fetchone()[0]}"
          f"（回填 {stats['vectors_filled']}｜非法 JSON 置 NULL {stats['bad_json']}"
          f"｜维度异常丢弃 {stats['bad_vector']}）")
    if not ok:
        sys.exit("[FAIL] 行数不一致——核对上方 DIFF 表后加 --truncate 重跑")
    print("== 迁移完成 ✔ 后续：按 docs/V2/03 附录 C 十项验收 ==")


if __name__ == "__main__":
    main()
