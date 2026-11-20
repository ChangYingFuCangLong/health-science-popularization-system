#!/usr/bin/env bash
# ============================================================
# 健康科普 RAG 系统 · WSL 侧 PostgreSQL 主库一键搭建（v2.0 / M9.5）
#
# ⛔ 隔离纪律（用户明令，先读这里）：
#   · 本脚本只操作全新目录 ~/health-rag-pg/（可用 -d 改），数据卷 <项目名>_pg-data；
#   · 绝不读写旧目录 ~/health-rag/、绝不 stop/rm 旧容器、绝不碰 health-rag_* 旧卷；
#   · 端口冲突（5432）→ 只 `docker stop <冲突容器>`（加 --stop-others 才会停别的容器）；
#   · 全程不执行 docker rm / docker compose down -v。
#
# 用法（WSL 终端，在仓库根目录执行）：
#   bash deploy/wsl-setup-pg.sh -y
#   bash deploy/wsl-setup-pg.sh -y --no-build     # 镜像已存在时跳过构建
#   bash deploy/wsl-setup-pg.sh -y --backup       # 附带 pg_dump 备份
# 流程：预检 → 建新目录 → 备齐配置/SQL/Dockerfile → build 镜像 → 起容器
#       → 等 healthcheck → 验证扩展/结构/分词/索引 → 汇总
# ============================================================

set -Eeuo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${HOME}/health-rag-pg"
ASSUME_YES=0
NO_BUILD=0
STOP_OTHERS=0
DO_BACKUP=0

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    -y|--yes)        ASSUME_YES=1 ;;
    --no-build)      NO_BUILD=1 ;;
    --stop-others)   STOP_OTHERS=1 ;;   # 默认不停任何容器（隔离纪律）
    -b|--backup)     DO_BACKUP=1 ;;
    -s|--src)        SRC="$2"; shift ;;
    -d|--dir)        DIR="$2"; shift ;;
    -h|--help)       usage ;;
    *) echo "未知参数：$1（-h 查看用法）" >&2; exit 1 ;;
  esac
  shift
done

# Windows 侧编辑可能留下 CRLF
if grep -qU $'\r' "$0" 2>/dev/null; then
  echo "[FAIL] 脚本含 CRLF。先执行： sed -i 's/\r$//' $0" >&2
  exit 1
fi

mkdir -p "$DIR/logs" "$DIR/sql" "$DIR/backups"
LOG="$DIR/logs/setup-pg-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

step() { echo; echo "==> $*"; }
ok()   { echo "    ✓ $*"; }
fail() { echo; echo "[FAIL] $*" >&2; echo "日志：$LOG" >&2; exit 1; }
# PG 查询（容器内自带 psql，宿主机无需安装客户端；-t 元组模式 -A 非对齐，不要用 MySQL 的 -N）
q() { docker compose exec -T postgres psql -U postgres -d health_rag -t -A -c "$1" 2>/dev/null; }

echo "=============================================================="
echo " 健康科普 RAG 系统 · PostgreSQL 主库搭建（与旧 MySQL 栈完全隔离）"
echo " 时间：$(date '+%F %T')   日志：$LOG"
echo " 工作目录：$DIR（新建，不触碰 ~/health-rag/）"
echo "=============================================================="

# ---------------- 1. 预检 ----------------
step "1/7 预检 Docker 环境"
command -v docker >/dev/null 2>&1 || fail "未找到 docker。请在 WSL 发行版内安装 Docker 引擎。"
if ! docker info >/dev/null 2>&1; then
  echo "    docker 守护进程未运行，尝试启动（sudo 免密失败则请手动：sudo service docker start）"
  sudo -n service docker start >/dev/null 2>&1 || true
  sleep 3
  docker info >/dev/null 2>&1 || fail "Docker 守护进程未运行。WSL 内执行： sudo service docker start"
fi
docker compose version >/dev/null 2>&1 || fail "未找到 docker compose v2 插件。"
ok "docker：$(docker --version)"

# ---------------- 2. 容器共存检查 ----------------
if [[ "$STOP_OTHERS" -eq 1 ]]; then
  step "2/7 停止可能冲突的运行中容器（只 stop，绝不 rm）"
  mapfile -t RUNNING < <(docker ps --format '{{.Names}}' | grep -v '^health-rag-postgres$' || true)
  for c in "${RUNNING[@]:-}"; do
    [[ -z "$c" ]] && continue
    docker stop "$c" >/dev/null 2>&1 || true
    echo "    ⏹ 已停止：$c（数据未动，docker start 可恢复）"
  done
else
  step "2/7 容器共存检查（默认不停任何容器）"
  CONFLICT="$(docker ps --format '{{.Names}} {{.Ports}}' | grep ':5432->' \
               | grep -v '^health-rag-postgres ' | awk '{print $1}' || true)"
  if [[ -n "$CONFLICT" ]]; then
    fail "端口 5432 被运行中的容器占用：$CONFLICT。处理：docker stop $CONFLICT（只停不删）或 -d 换目录后改 .env 的 PG_PORT"
  fi
  ok "无 5432 端口冲突；旧容器一律未触碰"
fi

# ---------------- 3. 建工作目录 + 备齐文件 ----------------
step "3/7 搭建工作目录 $DIR"
for f in "$SRC/deploy/pg/docker-compose.yml" "$SRC/deploy/pg/Dockerfile" \
         "$SRC/docs/sql/pg/00-extensions.sql" "$SRC/docs/sql/pg/01-schema.sql" \
         "$SRC/docs/sql/pg/02-seed.sql"; do
  [[ -f "$f" ]] || fail "缺少源文件：$f（检查 --src，当前 $SRC）"
done
[[ -d "$SRC/deploy/pg/vendor" ]] || fail "缺少钉版源码目录：$SRC/deploy/pg/vendor"
ok "源文件齐全"

# .env：优先复制仓库已配好的（含 PG_PASSWORD）；否则按 example 生成随机强密码
if [[ -f "$SRC/deploy/.env" ]]; then
  cp "$SRC/deploy/.env" "$DIR/.env"
  ok "已复制仓库 .env"
else
  PGPW="$(openssl rand -hex 12 2>/dev/null || head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  { echo "PG_PASSWORD=$PGPW"; echo "PG_PORT=5432"; } > "$DIR/.env"
  ok "未发现仓库 .env，已生成随机 PG_PASSWORD"
fi
chmod 600 "$DIR/.env"
grep -q '^PG_PASSWORD=.\+' "$DIR/.env" || fail ".env 缺少 PG_PASSWORD"

# SQL 三件套与构建上下文（Dockerfile + vendor/）就位，统一去 CRLF
cp "$SRC/docs/sql/pg/00-extensions.sql" "$SRC/docs/sql/pg/01-schema.sql" \
   "$SRC/docs/sql/pg/02-seed.sql" "$SRC/docs/sql/pg/increment-v4.sql" "$DIR/sql/"
ok "SQL 就位（01-schema 已是 v4.0 终态含 15 表；increment-v4.sql 仅存量库手工补齐用，initdb 不执行）"
rm -rf "$DIR/vendor"
cp -r "$SRC/deploy/pg/vendor" "$DIR/vendor"
cp "$SRC/deploy/pg/Dockerfile" "$DIR/Dockerfile"
sed -i 's/\r$//' "$DIR"/sql/*.sql "$DIR/Dockerfile"
ok "SQL 与构建上下文就位（已去 CRLF）"

# compose 执行副本：initdb 挂载路径改写为工作目录
sed 's#\.\./\.\./docs/sql/pg/#./sql/#g' "$SRC/deploy/pg/docker-compose.yml" > "$DIR/docker-compose.yml"
grep -q './sql/01-schema.sql' "$DIR/docker-compose.yml" || fail "compose 改写失败"
ok "已生成 $DIR/docker-compose.yml（initdb 指向 ./sql/）"

# ---------------- 4. 确认 ----------------
step "4/7 执行确认"
echo "    工作目录：$DIR（新）｜ 项目/数据卷名：$(basename "$DIR")_pg-data"
echo "    镜像：health-rag/postgres-zh:16.4（build 上下文：钉版 vendor 源码）"
if [[ -t 0 && "$ASSUME_YES" -eq 0 ]]; then
  read -r -p "    确认继续？[y/N] " ans
  [[ "$ans" =~ ^[Yy]$ ]] || { echo "已取消"; exit 0; }
else
  ok "非交互模式（-y），直接执行"
fi

# ---------------- 5. 构建镜像并启动 ----------------
cd "$DIR"
step "5/7 构建镜像并启动容器"
if [[ "$NO_BUILD" -eq 1 ]] && docker image inspect health-rag/postgres-zh:16.4 >/dev/null 2>&1; then
  ok "跳过构建（--no-build，镜像已存在）"
else
  docker compose build postgres || fail "镜像构建失败——请查看上方 Docker 构建日志（apt / 编译步骤）"
  ok "镜像构建完成"
fi
docker compose up -d postgres
ok "容器已启动（health-rag-postgres）"

# ---------------- 6. 等 healthcheck ----------------
step "6/7 等待健康检查通过"
for i in $(seq 1 40); do
  st="$(docker inspect --format '{{.State.Health.Status}}' health-rag-postgres 2>/dev/null || echo missing)"
  [[ "$st" == "healthy" ]] && { ok "postgres healthy（等待约 $((i*5)) 秒）"; break; }
  sleep 5
  [[ $i -eq 40 ]] && fail "postgres 未 healthy（最后状态：$st）。看日志：docker logs health-rag-postgres"
done

# ---------------- 7. 验证 ----------------
step "7/7 验证扩展 / 结构 / 分词 / 索引"

EXTS="$(q "SELECT string_agg(extname, ',' ORDER BY extname) FROM pg_extension WHERE extname IN ('vector','zhparser');")"
[[ "$EXTS" == "vector,zhparser" ]] || fail "扩展不齐：$EXTS（期望 vector,zhparser）——查看 initdb 日志：docker logs health-rag-postgres"
ok "扩展：$EXTS"

TOKENS="$(q "SELECT to_tsvector('chinese', '高血压患者的饮食注意事项');")"
echo "    分词样例：$TOKENS"
[[ "$TOKENS" == *高血压* && "$TOKENS" == *饮食* ]] || fail "zhparser 未按词切分——检查 00-extensions.sql 的 chinese 配置"
ok "中文词级分词正常"

TABLES="$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';")"
[[ "$TABLES" == "15" ]] || fail "表数量 $TABLES（期望 15）"
ok "业务表数量 = 15（9 核心 + 6 用户资产表，v4.0 终态）"

IDX="$(q "SELECT string_agg(indexname, ',' ORDER BY indexname) FROM pg_indexes WHERE tablename='kb_chunk';")"
ok "kb_chunk 索引：$IDX"
TRG="$(q "SELECT tgname FROM pg_trigger WHERE tgrelid='chat_message'::regclass AND NOT tgisinternal;")"
[[ "$TRG" == "trg_message_touch_session" ]] || fail "会话 touch 触发器缺失（$TRG）"
ok "chat_message → chat_session 触发器在位"

# DEF-1 回归（2026-09-19）：自然语言整句必须能召回（关键词路 = 分词后 OR + ts_rank_cd）
SENT_HITS="$(q "WITH q AS (SELECT to_tsquery('chinese', string_agg(quote_literal(lex), ' | ')) AS tsq
                  FROM unnest(tsvector_to_array(to_tsvector('chinese', '高血压患者日常饮食要注意什么'))) AS lex
                 WHERE length(lex) >= 2 OR lex NOT IN ('是','的','了','在','和','与','及','或','并','而','但','不','没','有','要','会','能','可','该','当','很','都','也','就','还','被','把','对','为','从','向','按','据','因','由','吗','呢','吧','啊','请','什么','怎么','怎样','如何','哪些','多少','可以','应该','需要','如果','因为','所以','但是','不过','以及','还是','为什么'))
                SELECT COUNT(*) FROM kb_chunk c, q WHERE NOT c.deleted AND c.tsv @@ q.tsq;")"
[[ "$SENT_HITS" =~ ^[0-9]+$ && "$SENT_HITS" -gt 0 ]] || fail "整句召回为 0（DEF-1 回归失败）——核对关键词召回 SQL 与 'chinese' 配置"
ok "整句召回回归通过（命中 $SENT_HITS 条）"

# 孤儿分块巡检（D9 删除级联约定的自检，必须为 0）
ORPH="$(q "SELECT COUNT(*) FROM kb_chunk c LEFT JOIN kb_document d ON d.id = c.document_id
            WHERE NOT c.deleted AND (d.id IS NULL OR d.deleted);")"
[[ "$ORPH" == "0" ]] || fail "发现 $ORPH 条孤儿分块（文档已删或不存在）——检查删除级联实现"
ok "无孤儿分块"

ROW0="$(q "SELECT COUNT(*) FROM sys_user;")"
if [[ "$ROW0" == "0" ]]; then
  echo "    数据卷为空但未灌入 seed？initdb 只在【空卷首次启动】执行——若此次为空卷，检查 docker logs"
else
  ok "库内已有数据（sys_user=$ROW0；全新 initdb 后期望 7，迁移/联调库以实际为准）"
fi

PENDING="$(q "SELECT COUNT(*) FILTER (WHERE embedding IS NULL) || '/' || COUNT(*) FROM kb_chunk WHERE NOT deleted;")"
ok "向量就绪度（pending/total）：$PENDING —— seed 状态即『全部未向量化』，属预期"

if [[ "$DO_BACKUP" -eq 1 ]]; then
  BK="$DIR/backups/health_rag-$(date +%Y%m%d-%H%M%S).sql"
  docker compose exec -T postgres pg_dump -U postgres health_rag > "$BK" 2>/dev/null
  ok "备份已生成：$BK（$(wc -c < "$BK") 字节）"
fi

# ---------------- 汇总 ----------------
echo
echo "----------------------------------------------"
echo " 表行数（COUNT(*) 确数）"
q "SELECT 'sys_user', COUNT(*) FROM sys_user UNION ALL
   SELECT 'kb_document', COUNT(*) FROM kb_document UNION ALL
   SELECT 'kb_chunk', COUNT(*) FROM kb_chunk UNION ALL
   SELECT 'chat_session', COUNT(*) FROM chat_session UNION ALL
   SELECT 'chat_message', COUNT(*) FROM chat_message UNION ALL
   SELECT 'message_feedback', COUNT(*) FROM message_feedback UNION ALL
   SELECT 'eval_dataset', COUNT(*) FROM eval_dataset UNION ALL
   SELECT 'eval_result', COUNT(*) FROM eval_result UNION ALL
   SELECT 'health_metric', COUNT(*) FROM health_metric UNION ALL
   SELECT 'user_portrait', COUNT(*) FROM user_portrait UNION ALL
   SELECT 'chat_health_fact', COUNT(*) FROM chat_health_fact UNION ALL
   SELECT 'health_kv', COUNT(*) FROM health_kv UNION ALL
   SELECT 'metric_def', COUNT(*) FROM metric_def UNION ALL
   SELECT 'user_kb_setting', COUNT(*) FROM user_kb_setting UNION ALL
   SELECT 'qa_session_document', COUNT(*) FROM qa_session_document" \
| awk -F'|' '{printf "   %-18s %s\n", $1, $2}'
echo "----------------------------------------------"
echo " 关键词检索抽查（高血压 OR 饮食，词级分词为 AND/OR 精确语义）："
q "SELECT id || ':' || LEFT(content, 24) FROM kb_chunk
   WHERE tsv @@ websearch_to_tsquery('chinese', '高血压 OR 饮食')
   ORDER BY ts_rank_cd(tsv, websearch_to_tsquery('chinese', '高血压 OR 饮食')) DESC LIMIT 3" \
| sed 's/^/   /'
echo "----------------------------------------------"
echo " 连接信息（Windows 侧 Spring Boot / DataGrip 用）"
echo "   PG    : localhost:$(grep '^PG_PORT=' "$DIR/.env" | cut -d= -f2)  库 health_rag  用户 postgres  密码见 $DIR/.env"
echo "   容器  : $(docker ps --format '{{.Names}}({{.Status}})' | grep health-rag-postgres)"
echo "   日志  : $LOG"
echo "----------------------------------------------"
echo " 常用命令（全部只作用于新栈）"
echo "   停止（保留数据）: docker compose -f $DIR/docker-compose.yml down   # 停容器不删卷"
echo "   重启            : docker compose -f $DIR/docker-compose.yml up -d"
echo "   备份            : docker compose -f $DIR/docker-compose.yml exec -T postgres pg_dump -U postgres health_rag > backup.sql"
echo "   存量迁移        : python deploy/migration/mysql2pg.py（需 MySQL 侧可连通）"
echo "   ⛔ 红线          : 不 docker rm / 不 down -v / 不动 ~/health-rag/ 旧栈"
echo "=============================================="
echo " 完成 ✔"
