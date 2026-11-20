# ============================================================
# 健康科普 RAG 系统 · 自建 PostgreSQL 镜像：postgres:16.4 + pgvector + zhparser
# 依据：docs/V2/03-数据库PostgreSQL迁移设计方案.md §5.3（已批准，04 §一 Q2 zhparser 照准）
#
# 构建上下文：deploy/pg/（本 Dockerfile + vendor/ 内钉版源码，全程无需访问外网仓库）
#   vendor/scws-1.2.3.tar.bz2     sha256=60d50ac3dc42cff3c0b16cb1cfee47d8cb8c8baa142a58bc62854477b81f1af5
#   vendor/zhparser-master.tar.gz sha256=0ab8b596c5e000002e9838077a7783a7bc97c4a8dbd1b7b9404435e27d62b181
# 构建：docker compose build postgres（在 deploy/ 或 WSL ~/health-rag/ 工作副本内）
# 产物：health-rag/postgres-zh:16.4
#
# ⚠️ apt 步骤（pgvector / PG 编译头）仍需访问 Debian/PGDG 软件源；若构建机无法出网，
#    可预置离线 apt 包或改用带 pgvector 的预构建基础镜像。
# ============================================================

FROM postgres:16.4

# 国内网络加速：Debian 主源 → USTC（已验证快）。PGDG 无国内镜像（TUNA/USTC 均 404），
# 保留官方源但强制 http://（官方镜像不含 ca-certificates，https 源证书验证失败；
# 包完整性由 apt 签名保证，不受传输协议影响）。overseas 构建可整段删除，不影响产物。
RUN set -eux; \
    sed -i 's#https\?://deb\.debian\.org#http://mirrors.ustc.edu.cn#g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null \
        || sed -i 's#https\?://deb\.debian\.org#http://mirrors.ustc.edu.cn#g' /etc/apt/sources.list; \
    sed -i 's#https\?://apt\.postgresql\.org#http://apt.postgresql.org#g' \
        /etc/apt/sources.list.d/pgdg.list 2>/dev/null || true

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        bzip2 \
        ca-certificates \
        postgresql-server-dev-16 \
        postgresql-16-pgvector \
    && rm -rf /var/lib/apt/lists/*

# 钉版源码（构建上下文自带，不 clone 不下载，保证可重建）
COPY vendor/scws-1.2.3.tar.bz2 vendor/zhparser-master.tar.gz /tmp/

# 1) SCWS：zhparser 的分词底层库（编译 + 安装 + 动态链接缓存）
# 2) zhparser：PG 中文文本搜索解析器（PGXS 构建）
RUN tar -xjf /tmp/scws-1.2.3.tar.bz2 -C /tmp \
    && cd /tmp/scws-1.2.3 \
    && ./configure --prefix=/usr/local \
    && make -j"$(nproc)" \
    && make install \
    && ldconfig \
    && tar -xzf /tmp/zhparser-master.tar.gz -C /tmp \
    && make -C /tmp/zhparser-master USE_PGXS=1 \
    && make -C /tmp/zhparser-master USE_PGXS=1 install \
    && rm -rf /tmp/scws-1.2.3* /tmp/zhparser-master* /tmp/scws-1.2.3.tar.bz2 /tmp/zhparser-master.tar.gz

# 验证（构建期自检，失败即中止构建）：
#   pg_config 存在、扩展控制文件就位
RUN pg_config --version \
    && test -f "$(pg_config --sharedir)/extension/zhparser.control" \
    && test -f "$(pg_config --sharedir)/extension/vector.control"

# 入口/数据目录沿用官方镜像（initdb 机制见 deploy/README-pg.md §三）
