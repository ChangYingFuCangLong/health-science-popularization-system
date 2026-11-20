# 健康科普 RAG 系统

基于检索增强生成（RAG）的健康科普问答系统。用户端提供 AI 问答、知识探索、健康档案、个人知识库与文档解惑；管理端提供知识库文档、分块、会话反馈与评测管理。

> 本科毕业设计《基于检索增强生成（RAG）的健康科普系统的设计与实现》。
> 系统输出仅供健康科普参考，不构成诊疗建议。

## 功能

- **AI 问答**：SSE 流式输出、引用溯源、检索置信度不足时拒答
- **混合检索**：pgvector 向量召回 + 中文全文召回，RRF 融合
- **健康中心**：健康指标、体检报告识读、个人健康档案
- **个人知识库**：上传文档、个人资料检索与问答
- **知识浏览**：分类、文章详情、来源展示
- **管理端**：知识库文档与分块、会话反馈、评测集与结果复核

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | JDK 21 · Spring Boot 3.5 · Spring AI 1.1 · MyBatis-Plus 3.5 · JWT · SSE |
| 存储 | PostgreSQL 16 + pgvector + zhparser，单库 |
| 检索 | 向量召回 + 中文全文召回，RRF 融合；可选 rerank 实验组 |
| 前端 | Vue 3.5 · TypeScript 5.9 · Vite 7 · Pinia 3 · Tailwind 4 · Element Plus · ECharts |
| 模型 | 对话 DeepSeek（OpenAI 兼容）；向量 Ollama `bge-m3`；rerank / ASR 走百炼原生接口 |

## 目录

```text
.
├─ README.md
├─ docs/sql/pg/                 建表、种子与增量 SQL
├─ deploy/                      数据库编排与初始化脚本
├─ health-rag-backend/          Spring Boot 后端
└─ health-rag-frontend/         Vue 3 前端
```

后端 Java 根包为 `com.healthscience.rag`；前端 API 统一走 `/api`，由 Vite 代理到 8080。

## 环境要求

- JDK 21、Maven 3.9+
- Node.js >= 22.12
- WSL2 + Docker（PostgreSQL 容器）
- Ollama，已拉取 `bge-m3`
- DeepSeek API Key（对话模型）

## 快速开始

顺序固定：**数据库 → 后端 → 前端**。

### 1. 数据库

在 WSL2 的 Ubuntu 发行版中，从仓库根目录执行：

```bash
bash deploy/wsl-setup-pg.sh -y
```

脚本会构建带 `pgvector` 与 `zhparser` 的 PostgreSQL 镜像、启动 `health-rag-postgres` 容器，并导入 `docs/sql/pg` 下的建表与种子 SQL。

已有数据卷时只需启动容器，不要重建：

```bash
cd ~/health-rag-pg && docker compose up -d
```

> 不要执行 `docker compose down -v`，会删除数据卷。

### 2. 后端

```bash
cd health-rag-backend

# 在模块根目录创建 .env（已被 .gitignore 忽略，严禁提交）
# Copy-Item .env.example .env
# 然后填写 PG_PASSWORD、DEEPSEEK_API_KEY 等真实值

mvn spring-boot:run
```

探活与接口文档：

- `GET http://localhost:8080/api/ping` → `UP`
- `http://localhost:8080/swagger-ui.html`

默认向量提供方是本地 Ollama：

```bash
ollama serve
ollama pull bge-m3
```

### 3. 前端

```bash
cd health-rag-frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。开发环境由 Vite 将 `/api` 代理到后端 8080。

## 测试与构建

```bash
# 后端：单元测试 + ArchUnit 架构约束
cd health-rag-backend && mvn test

# 后端打包
mvn -DskipTests clean package

# 前端：类型检查、单元测试、构建
cd health-rag-frontend
npm run typecheck
npm run test:unit
npm run build
```

## 配置说明

| 配置 | 默认值 | 说明 |
|---|---|---|
| `server.port` | `8080` | 后端端口 |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/health_rag` | `stringtype=unspecified` 供 JSONB 直写 |
| `spring.ai.openai.base-url` | `https://api.deepseek.com` | 对话模型 OpenAI 兼容地址 |
| `health.rag.embedding-provider` | `ollama` | 本地 `bge-m3`，1024 维 |
| `health.rag.default-mode` | `hybrid` | `naive` / `hybrid` / `hybrid_rerank` / `none` |
| `jwt.expire-hours` | `24` | JWT 有效期 |

切换 embedding 模型后必须全量重建向量，不同模型的向量空间不可混用。

## 安全约定

- `.env`、`target/`、`data/`、`node_modules/`、`dist/` 均已忽略，不要提交
- 数据库密码、模型 API Key、JWT 生产密钥只通过环境变量或本地 `.env` 注入
- 仓库中的 JWT 默认值仅供开发，部署前必须替换
- 测试截图与构建产物不入库

## 许可

仅用于学习与毕业设计展示；第三方依赖遵循其各自许可证。
