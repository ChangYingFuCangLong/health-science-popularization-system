#!/usr/bin/env bash
# ============================================================
# 健康科普 RAG 系统 · WSL 一侧数据库环境一键搭建（V1 MySQL 栈，归档）
#
# ⚠️ 归档说明：本脚本对应的 V1 MySQL SQL（docs/sql/schema.sql、seed.sql）未随本仓库提供，
#    当前版本不可直接执行；日常开发与部署请使用 deploy/wsl-setup-pg.sh（PostgreSQL 主线）。
#
# 用法（在你的 WSL 发行版终端里执行）：
#   bash deploy/wsl-setup.sh -y
#   bash deploy/wsl-setup.sh -y --backup
#   bash deploy/wsl-setup.sh -y -f          # 强制重灌模拟数据
#   bash deploy/wsl-setup.sh -y --no-stop-others
#
# 流程：预检 → 停无关容器 → 建目录 → 备齐配置与 SQL → 起容器
#       → 等 healthcheck → 验证结构 → （必要时）灌模拟数据 → 汇总
# 特点：全程 fail-fast，日志落到 $DIR/logs/setup-<时间戳>.log
#
# ⚠️ 本脚本不执行 `docker compose down -v`（那会删除数据卷、数据不可恢复）。
#    需要重建时请先备份：bash deploy/wsl-setup.sh --backup
# ============================================================

set -Eeuo pipefail

# ---------------- 参数 ----------------
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${HOME}/health-rag"
ASSUME_YES=0
FORCE_SEED=0
STOP_OTHERS=1
DO_BACKUP=0
PROFILE="mysql redis"   # 默认起 MySQL + Redis

usage() {
  sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -y|--yes)           ASSUME_YES=1 ;;
    -f|--force-seed)    FORCE_SEED=1 ;;
    --no-stop-others)   STOP_OTHERS=0 ;;
    -b|--backup)        DO_BACKUP=1 ;;
    -s|--src)           SRC="$2";  shift ;;
    -d|--dir)           DIR="$2";  shift ;;
    --mysql-only)       PROFILE="mysql" ;;
    -h|--help)          usage ;;
    *) echo "未知参数：$1（用 -h 查看用法）" >&2; exit 1 ;;
  esac
  shift
done

# ---------------- 基础防护 ----------------
# Windows 侧编辑可能留下 CRLF，会让 bash 报 "\r: command not found"
if grep -qU $'\r' "$0" 2>/dev/null; then
  echo "[FAIL] 脚本含 CRLF 换行。先执行： sed -i 's/\r$//' $0" >&2
  exit 1
fi

mkdir -p "$DIR/logs" "$DIR/sql" "$DIR/backups"
LOG="$DIR/logs/setup-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

step() { echo; echo "==> $*"; }
ok()   { echo "    ✓ $*"; }
fail() { echo; echo "[FAIL] $*" >&2; echo "日志：$LOG" >&2; exit 1; }

# MySQL 查询（容器内自带 mysql 客户端，宿主机无需安装任何东西）
q() {
  docker compose exec -T mysql \
    mysql -uroot -p"$PW" --default-character-set=utf8mb4 -N -B -e "$1" health_rag 2>/dev/null
}

echo "=============================================================="
echo " 健康科普 RAG 系统 · 数据库环境搭建"
echo " 时间：$(date '+%F %T')   日志：$LOG"
echo "=============================================================="

# ---------------- 1. 预检 ----------------
step "1/8 预检 Docker 环境"
command -v docker >/dev/null 2>&1 || fail "未找到 docker。请在 WSL 发行版内安装 Docker 引擎。"
docker info >/dev/null 2>&1 || fail "Docker 守护进程未运行。WSL 内执行： sudo service docker start"
docker compose version >/dev/null 2>&1 || fail "未找到 docker compose v2 插件。"
ok "docker：$(docker --version)"
ok "compose：$(docker compose version)"

# ---------------- 2. 停掉无关容器 ----------------
if [[ "$STOP_OTHERS" -eq 1 ]]; then
  step "2/8 停止无关容器（保留 health-rag-*）"
  mapfile -t OTHERS < <(docker ps --format '{{.Names}}|{{.Image}}' | grep -v '^health-rag-' || true)
  if [[ ${#OTHERS[@]} -eq 0 ]]; then
    ok "当前没有其他运行中容器"
  else
    for c in "${OTHERS[@]}"; do
      name="${c%%|*}"
      image="${c##*|}"
      docker stop "$name" >/dev/null 2>&1 || true
      echo "    ⏹ 已停止：$name（镜像 $image）"
    done
    ok "共停止 ${#OTHERS[@]} 个无关容器（数据未删除，可用 docker start 恢复）"
  fi
else
  step "2/8 跳过停止无关容器（--no-stop-others）"
fi

# ---------------- 3. 建目录 + 备齐配置与 SQL ----------------
step "3/8 搭建工作目录 $DIR"
for f in "$SRC/deploy/docker-compose.yml" "$SRC/deploy/.env" "$SRC/deploy/.env.example" \
         "$SRC/docs/sql/schema.sql" "$SRC/docs/sql/seed.sql"; do
  [[ -f "$f" ]] || fail "缺少源文件：$f（检查 --src 参数，当前 $SRC）"
done
ok "源文件齐全"

# .env：优先用仓库里已配好的，否则按 example 生成随机强密码
if [[ -f "$SRC/deploy/.env" ]]; then
  cp "$SRC/deploy/.env" "$DIR/.env"
  ok "已复制仓库 .env"
else
  sed "s#^MYSQL_ROOT_PASSWORD=.*#MYSQL_ROOT_PASSWORD=$(openssl rand -hex 12 2>/dev/null || head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')#" \
    "$SRC/deploy/.env.example" > "$DIR/.env"
  ok "未发现 .env，已按 .env.example 生成随机密码"
fi
chmod 600 "$DIR/.env"

PW="$(grep '^MYSQL_ROOT_PASSWORD=' "$DIR/.env" | head -1 | cut -d= -f2-)"
[[ -n "$PW" && "$PW" != "CHANGE_ME" ]] || fail "MYSQL_ROOT_PASSWORD 未配置（仍是 CHANGE_ME），请编辑 $DIR/.env"

cp "$SRC/docs/sql/schema.sql" "$DIR/sql/schema.sql"
cp "$SRC/docs/sql/seed.sql"   "$DIR/sql/seed.sql"
ok "SQL 就位：schema.sql / seed.sql"

# 生成本地 compose：把 SQL 挂载路径从仓库位置改写为 WSL 工作目录（纯 ext4，避开 /mnt 挂载点）
sed -e 's#\.\./docs/sql/schema\.sql#./sql/schema.sql#g' \
    -e 's#\.\./docs/sql/seed\.sql#./sql/seed.sql#g' \
    "$SRC/deploy/docker-compose.yml" > "$DIR/docker-compose.yml"
grep -q './sql/schema.sql' "$DIR/docker-compose.yml" || fail "compose 改写失败：未找到 ./sql/schema.sql"
grep -q './sql/seed.sql'   "$DIR/docker-compose.yml" || fail "compose 改写失败：未找到 ./sql/seed.sql"
ok "已生成 $DIR/docker-compose.yml（SQL 挂载指向 WSL 本地 ext4）"

# ---------------- 4. 确认 ----------------
step "4/8 执行确认"
echo "    工作目录：$DIR"
echo "    启动服务：$PROFILE"
echo "    端口：MySQL $(grep '^MYSQL_PORT=' "$DIR/.env" | cut -d= -f2) ／ Redis $(grep '^REDIS_PORT=' "$DIR/.env" | cut -d= -f2)"
echo "    root 密码：$PW"
if [[ -t 0 && "$ASSUME_YES" -eq 0 ]]; then
  read -r -p "    确认继续？[y/N] " ans
  [[ "$ans" =~ ^[Yy]$ ]] || { echo "已取消"; exit 0; }
else
  ok "非交互模式（-y），直接执行"
fi

# ---------------- 5. 起容器 ----------------
step "5/8 启动容器（首次会执行 01-schema.sql + 02-seed.sql）"
cd "$DIR"
docker compose up -d $PROFILE
ok "容器已启动"

# ---------------- 6. 等 healthcheck ----------------
step "6/8 等待 MySQL 健康检查通过"
for i in $(seq 1 60); do
  st="$(docker inspect --format '{{.State.Health.Status}}' health-rag-mysql 2>/dev/null || echo missing)"
  [[ "$st" == "healthy" ]] && { ok "MySQL healthy（等待 $((i*3)) 秒）"; break; }
  sleep 3
  [[ $i -eq 60 ]] && fail "MySQL 60 次轮询未 healthy（最后状态：$st）。看日志：docker logs health-rag-mysql"
done

if [[ "$PROFILE" == *redis* ]]; then
  for i in $(seq 1 30); do
    if docker exec health-rag-redis redis-cli ping 2>/dev/null | grep -q PONG; then
      ok "Redis PONG"; break
    fi
    sleep 2
    [[ $i -eq 30 ]] && fail "Redis 无响应"
  done
fi

# ---------------- 7. 验证结构 ----------------
step "7/8 验证数据库结构"
NGRAM="$(q "SHOW VARIABLES LIKE 'ngram_token_size';" | awk '{print $2}')"
[[ "$NGRAM" == "2" ]] || fail "ngram_token_size=$NGRAM（期望 2）—— 中文全文检索会静默失效"
ok "ngram_token_size = 2"

TABLES="$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='health_rag' AND table_type='BASE TABLE';")"
[[ "$TABLES" == "9" ]] || fail "表数量 $TABLES（期望 9）"
ok "业务表数量 = 9"

# ---------------- 8. 模拟数据 ----------------
step "8/8 模拟数据"
USER_CNT="$(q "SELECT COUNT(*) FROM sys_user;")"
if [[ "$USER_CNT" -eq 0 || "$FORCE_SEED" -eq 1 ]]; then
  docker compose exec -T mysql \
    mysql -uroot -p"$PW" --default-character-set=utf8mb4 health_rag < "$DIR/sql/seed.sql" 2>/dev/null
  ok "已灌入模拟数据"
else
  ok "业务表已有数据（sys_user=$USER_CNT），跳过灌入；需重灌请加 -f"
fi

if [[ "$DO_BACKUP" -eq 1 ]]; then
  BK="$DIR/backups/health_rag-$(date +%Y%m%d-%H%M%S).sql"
  docker compose exec -T mysql \
    mysqldump -uroot -p"$PW" --databases health_rag > "$BK" 2>/dev/null
  ok "备份已生成：$BK（$(wc -c < "$BK") 字节）"
fi

# ---------------- 汇总 ----------------
# 行数一律用 COUNT(*) 取确数。
# ⛔ 不要用 information_schema.table_rows：那是 InnoDB 的**估算值**，
#    实测会把 kb_document(7) / chat_message(12) 显示成 0，极具误导性。
COUNT_SQL=""
for t in sys_user kb_document kb_chunk chat_session chat_message \
         message_feedback eval_dataset eval_result; do
  COUNT_SQL+="SELECT '$t' AS t, COUNT(*) AS total, SUM(CASE WHEN deleted=0 THEN 1 ELSE 0 END) AS active FROM \`$t\` UNION ALL "
done
# kb_vector_meta 是辅助单行表，建表时未设 deleted 审计字段，单独计数
COUNT_SQL+="SELECT 'kb_vector_meta', COUNT(*), COUNT(*) FROM kb_vector_meta"

echo
echo "----------------------------------------------"
echo " 表行数（COUNT(*) 确数：total=全部 / active=未逻辑删除）"
q "$COUNT_SQL" | awk -F'\t' '{printf "   %-18s total=%-5s active=%s\n", $1, $2, $3}'
echo "----------------------------------------------"
echo " 中文全文检索抽查（高血压 饮食）："
q "SELECT id, LEFT(content,24) FROM kb_chunk WHERE MATCH(content) AGAINST ('高血压 饮食' IN NATURAL LANGUAGE MODE) ORDER BY MATCH(content) AGAINST ('高血压 饮食' IN NATURAL LANGUAGE MODE) DESC LIMIT 3;"
echo "----------------------------------------------"
echo " 连接信息（Windows 侧 Spring Boot / DataGrip 用）"
echo "   MySQL : localhost:$(grep '^MYSQL_PORT=' "$DIR/.env" | cut -d= -f2)  库 health_rag  用户 root  密码 $PW"
[[ "$PROFILE" == *redis* ]] && echo "   Redis : localhost:$(grep '^REDIS_PORT=' "$DIR/.env" | cut -d= -f2)"
echo "   容器  : $(docker ps --format '{{.Names}}({{.Status}})' | grep health-rag | tr '\n' ' ')"
echo "   日志  : $LOG"
echo "----------------------------------------------"
echo " 常用命令"
echo "   停止（保留数据）: docker compose -f $DIR/docker-compose.yml down"
echo "   重启            : docker compose -f $DIR/docker-compose.yml up -d"
echo "   备份            : docker compose -f $DIR/docker-compose.yml exec -T mysql mysqldump -uroot -p$PW --databases health_rag > backup.sql"
echo "   ⛔ 禁止 down -v  : 会删除数据卷，数据不可恢复"
echo "=============================================="
echo " 完成 ✔"
