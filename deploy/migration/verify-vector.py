#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""迁移后抽样校验：从 PG 抽带回向量的 kb_chunk → 回查 MySQL 的旧 embedding_id
→ 与 SimpleVectorStore JSON 原始向量逐值比对（embedding_id 列在 v2 已退役，映射只能问 MySQL）。"""
import json
import random
import sys
from pathlib import Path

import psycopg2
import pymysql

ROOT = Path(__file__).resolve().parents[2]
ENV = {}
for line in open(ROOT / "deploy/.env", encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, _, v = line.partition("=")
        ENV[k] = v

pg = psycopg2.connect(host="127.0.0.1", port=5432, user="postgres",
                      password=ENV["PG_PASSWORD"], dbname="health_rag")
cur = pg.cursor()
cur.execute("SELECT id, embedding::text FROM kb_chunk "
            "WHERE embedding IS NOT NULL ORDER BY random() LIMIT 5")
rows = cur.fetchall()
if not rows:
    sys.exit("[FAIL] PG 中没有带回向量的 kb_chunk")

my = pymysql.connect(host="127.0.0.1", port=3306, user="root",
                     password=ENV["MYSQL_ROOT_PASSWORD"], database="health_rag",
                     charset="utf8mb4")
mycur = my.cursor()
mycur.execute("SELECT id, embedding_id FROM kb_chunk WHERE id IN (%s)"
              % ",".join(str(r[0]) for r in rows))
eid_by_cid = dict(mycur.fetchall())

store = json.loads(open(ROOT / "health-rag-backend/data/vector-store.json",
                        encoding="utf-8").read())

ok = 0
for cid, etext in rows:
    eid = eid_by_cid.get(cid)
    src = store.get(eid) if eid else None
    if src is None:
        print(f"[WARN] chunk={cid} embedding_id={eid} 在 JSON 中不存在")
        continue
    pg_vec = [float(x) for x in etext.strip("[]").split(",")]
    src_vec = [float(x) for x in src["embedding"]]
    if len(pg_vec) != len(src_vec):
        print(f"[FAIL] chunk={cid} 维度不一致 pg={len(pg_vec)} json={len(src_vec)}")
        continue
    max_diff = max(abs(a - b) for a, b in zip(pg_vec, src_vec))
    status = "OK " if max_diff < 1e-6 else "DIFF"
    ok += status == "OK "
    print(f"[{status}] chunk={cid} eid={str(eid)[:8]}… 维度={len(pg_vec)} 最大误差={max_diff:.2e}")

print(f"抽样 {len(rows)} 条，一致 {ok} 条")
sys.exit(0 if ok == len(rows) else 1)
