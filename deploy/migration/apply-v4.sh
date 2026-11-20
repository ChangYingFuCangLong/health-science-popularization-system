#!/usr/bin/env bash
# v4.0 终态增量执行器：pg_dump 备份 → 应用 increment-v4.sql（幂等）→ 立即校验
# 用法：在仓库根执行 bash deploy/migration/apply-v4.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIR="$HOME/health-rag-pg"
SRC="${SCRIPT_DIR}/../../docs/sql/pg/increment-v4.sql"

cd "$DIR"

echo "==> 1/3 备份（pg_dump，先备份是执行红线）"
BK="backups/health_rag-pre-v4-$(date +%Y%m%d-%H%M%S).sql"
docker compose exec -T postgres pg_dump -U postgres health_rag > "$BK"
echo "    备份：$BK（$(wc -c < "$BK") 字节）"

echo "==> 2/3 应用增量（increment-v4.sql，幂等可重跑）"
cp "$SRC" sql/increment-v4.sql
sed -i 's/\r$//' sql/increment-v4.sql
docker exec -i health-rag-postgres psql -U postgres -d health_rag -v ON_ERROR_STOP=1 < sql/increment-v4.sql

echo "==> 3/3 执行后校验"
PG() { docker exec health-rag-postgres psql -U postgres -d health_rag -t -A -c "$1"; }
PG "SELECT 'tables=' || COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';"
PG "SELECT 'kb_document_scope=' || string_agg(DISTINCT scope, ',') FROM kb_document;"
PG "SELECT 'chat_session_kind=' || string_agg(DISTINCT kind, ',') FROM chat_session;"
PG "SELECT 'metric_def_system=' || COUNT(*) FROM metric_def WHERE owner_user_id IS NULL;"
PG "SELECT 'health_metric_source=' || string_agg(DISTINCT source, ',') FROM health_metric;"
PG "SELECT 'metric_dangling=' || COUNT(*) FROM health_metric m
      WHERE NOT EXISTS (SELECT 1 FROM metric_def d WHERE d.metric_key = m.type AND NOT d.deleted);"
echo "==> 完成 ✔（异常请核对上方输出与 docs/sql/pg/increment-v4.sql 尾部校验清单）"
