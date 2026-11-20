#!/usr/bin/env bash
# 迁移前预检：MySQL 侧行数、向量就绪度、四处唯一索引冲突扫描（只读）
# 用法：在仓库根执行 bash deploy/migration/precheck-mysql.sh
set -euo pipefail

PW="$(grep '^MYSQL_ROOT_PASSWORD=' "$HOME/health-rag/.env" | head -1 | cut -d= -f2-)"
[[ -n "$PW" ]] || { echo "[FAIL] 未读到 MYSQL_ROOT_PASSWORD"; exit 1; }

for i in $(seq 1 30); do
  st="$(docker inspect --format '{{.State.Health.Status}}' health-rag-mysql 2>/dev/null || echo missing)"
  [ "$st" = "healthy" ] && { echo "mysql healthy"; break; }
  sleep 3
  [ "$i" -eq 30 ] && { echo "[FAIL] mysql 未 healthy"; exit 1; }
done

M() { docker exec health-rag-mysql mysql -uroot -p"$PW" --default-character-set=utf8mb4 -N -B -e "$1" health_rag 2>/dev/null; }

echo "=== 行数 ==="
M "SELECT 'sys_user', COUNT(*) FROM sys_user
   UNION ALL SELECT 'kb_document', COUNT(*) FROM kb_document
   UNION ALL SELECT 'kb_chunk', COUNT(*) FROM kb_chunk
   UNION ALL SELECT 'chat_session', COUNT(*) FROM chat_session
   UNION ALL SELECT 'chat_message', COUNT(*) FROM chat_message
   UNION ALL SELECT 'message_feedback', COUNT(*) FROM message_feedback
   UNION ALL SELECT 'eval_dataset', COUNT(*) FROM eval_dataset
   UNION ALL SELECT 'eval_result', COUNT(*) FROM eval_result"

echo "=== 向量就绪度（总块数 / 带 embedding_id）==="
M "SELECT COUNT(*), SUM(embedding_id IS NOT NULL) FROM kb_chunk"

echo "=== 唯一性冲突扫描（应全部为 0 行）==="
echo "[kb_chunk (document_id, chunk_index) 重复]"
M "SELECT document_id, chunk_index, COUNT(*) c FROM kb_chunk GROUP BY 1,2 HAVING c>1"
echo "[message_feedback (message_id, user_id) 重复]"
M "SELECT message_id, user_id, COUNT(*) c FROM message_feedback GROUP BY 1,2 HAVING c>1"
echo "[eval_result (batch_no, dataset_id, strategy) 重复]"
M "SELECT batch_no, dataset_id, strategy, COUNT(*) c FROM eval_result GROUP BY 1,2,3 HAVING c>1"
echo "[sys_user 活跃同名]"
M "SELECT username, COUNT(*) c FROM sys_user WHERE deleted=0 GROUP BY 1 HAVING c>1"
echo "=== 预检结束（无 [FAIL] 即可迁移）==="
