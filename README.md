# AI 简历助手

AI 简历助手是一个面向求职准备的前后端分离应用。它集中管理简历、岗位 JD、项目证据和面试记录，并通过文本模拟、录音模拟、AI 复盘和薄弱点训练帮助用户形成可追踪的面试准备闭环。
<img width="2880" height="1325" alt="image" src="https://github.com/user-attachments/assets/fdca5b85-ec49-4d69-bd15-eb6b56d4e355" />

## 1. 项目简介与核心功能

### 已实现功能

- **账户与权限**：使用 Supabase Auth 邮箱/密码登录；前端携带 JWT，后端校验 JWT 并按当前用户隔离数据。
- **首页仪表盘**：展示资料、面试包、待复盘、训练任务等概览，汇总近期活动和薄弱点，并支持维护冲刺清单。
- **资料库**：上传、预览、下载和删除 PDF、DOC、DOCX 简历文件；服务端提取简历文本。支持管理岗位 JD、项目证据卡和面试包，并将资料组合到一次面试中。
- **真实面试记录**：创建、编辑和删除面试；维护问题、回答和自评；支持粘贴转写文本按空行分段；支持上传录音、语音转写、AI 识别问答后检查并加入面试记录。
- **AI 复盘**：根据面试问题、回答和关联资料生成复盘报告、准备度、逐题建议和薄弱点标签；支持查看和删除历史复盘。
- **AI 文本模拟**：基于面试包资料生成 4 道主问题；回答主问题后最多生成 1 道追问，支持跳过、逐题 AI 反馈，并在完成后保存为正式面试记录。
- **AI 录音模拟**：进行 10 道题的录音模拟，每题限时 5 分钟；支持浏览器录音、语音转写、回答确认和逐题反馈，完成后可形成正式面试记录。
- **薄弱点与训练任务**：按复盘中的薄弱点聚合来源面试，查看薄弱点详情，并创建、编辑和删除训练任务。
- **AI 对话**：创建带可选面试包、面试、复盘或薄弱点上下文的会话；保存历史消息，调用 AI 回复，并支持删除会话。
- **私有文件存储**：简历文件和录音通过服务端访问 Supabase 私有 Storage；浏览器不接触 Service Role Key。

### 当前边界

- AI 录音模拟固定为 10 道题、每题 5 分钟；单题录音不超过 10 MiB。
- 真实面试录音导入支持 WebM、Ogg、MP3、MP4/M4A、WAV，单文件不超过 25 MiB。
- 简历上传支持 PDF、DOC、DOCX，单文件不超过 10 MiB；扫描件或受保护文件可能无法提取正文。
- 本项目仅覆盖本地开发启动，不包含生产部署配置。

## 2. 技术栈与环境要求

| 模块 | 技术与版本 |
| --- | --- |
| 前端 | Next.js 15.2.4、React 19.0.0、TypeScript 5.8.2 |
| 后端 | Java 21、Spring Boot 3.4.3、Spring Security、Spring JDBC |
| 数据库 | PostgreSQL / Supabase PostgreSQL；未配置数据库连接时默认使用 H2 内存数据库 |
| 数据库迁移 | Flyway，当前迁移脚本为 V1 至 V18 |
| 文件解析 | Apache PDFBox 3.0.8、Apache POI 5.5.1 |
| 认证与存储 | Supabase Auth、Supabase 私有 Storage |
| AI | OpenAI 兼容 Chat Completions API；OpenAI 兼容音频转写 API 或火山引擎音频转写 |
| 测试 | JUnit 5、Spring Boot Test、Spring Security Test、H2 |

最低环境：Node.js 20+、pnpm 9+、JDK 21+、Maven 3.9+、Git。

需要一个 Supabase 项目用于 Auth；使用简历上传或录音功能时还需要私有 Storage bucket。后端默认使用 H2 内存数据库，因此最小本地启动不要求另外安装 PostgreSQL；重启后 H2 数据会丢失。需要持久化数据时配置 Supabase PostgreSQL。

## 3. 本地启动与运行指南

### 3.1 拉取代码

```powershell
git clone https://github.com/Lzephyr-w/interview-agent-app.git
cd interview-agent-app
```

### 3.2 准备 Supabase

1. 创建 Supabase 项目，并在 Authentication 中准备一个邮箱/密码用户。当前前端没有单独的注册页。
2. 创建以下私有 Storage bucket，保持 Public 关闭：

   - `resume-files`：简历原文件
   - `ai-mock-audio`：AI 录音模拟和真实面试录音导入文件

### 3.3 创建环境配置

在项目目录执行：

```powershell
Copy-Item web/.env.example web/.env.local
Copy-Item server/.env.example server/.env.local
```

编辑 `web/.env.local`：

| 变量 | 说明 |
| --- | --- |
| `NEXT_PUBLIC_API_BASE_URL` | Java 后端地址，默认 `http://localhost:8080` |
| `NEXT_PUBLIC_SUPABASE_URL` | Supabase Project URL |
| `NEXT_PUBLIC_SUPABASE_ANON_KEY` | Supabase 公共 anon/publishable key，不能填 Service Role Key |

编辑 `server/.env.local`：

| 变量 | 说明 |
| --- | --- |
| `SUPABASE_URL` | Supabase Project URL，必填 |
| `SUPABASE_STORAGE_URL` | 通常为 `${SUPABASE_URL}/storage/v1` |
| `SUPABASE_STORAGE_SERVICE_KEY` | 服务端访问私有 bucket 的 Service Role Key，不能提交到 Git |
| `AI_REVIEW_API_URL` | OpenAI 兼容 Chat Completions 地址；AI 复盘和模拟功能需要 |
| `AI_REVIEW_API_KEY` | AI 模型服务端密钥 |
| `AI_REVIEW_MODEL` | AI 模型名 |
| `AI_TRANSCRIPTION_API_URL` | OpenAI 兼容音频转写地址，使用该方式时填写 |
| `AI_TRANSCRIPTION_API_KEY` | 音频转写服务密钥 |
| `AI_TRANSCRIPTION_MODEL` | 音频转写模型名 |
| `VOLCENGINE_SPEECH_API_KEY` | 可选的火山引擎转写密钥，与 OpenAI 兼容转写二选一 |

默认可将 `SUPABASE_JWT_SECRET`、`SUPABASE_JWT_JWK_KID`、`SUPABASE_JWT_JWK_X`、`SUPABASE_JWT_JWK_Y` 留空，后端通过 Supabase JWKS 校验 JWT；Legacy HS256 或网络受限时再填写对应项。示例中的 `replace-with-*` 不能原样保留。

数据库配置二选一：

- **H2 内存数据库**：从 `server/.env.local` 删除或注释 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 三行，使用 `application.yml` 默认值。
- **Supabase PostgreSQL**：填写上述三个变量，JDBC URL 建议包含 `sslmode=require&currentSchema=interview_agent`。

不要提交 `server/.env.local`、`web/.env.local`、Service Role Key、数据库密码或模型密钥。

### 3.4 安装前端依赖

```powershell
cd web
pnpm install
```

### 3.5 初始化数据库

不需要手工执行迁移。后端启动时 Flyway 会自动创建 `interview_agent` schema，并按顺序执行 V1 至 V18；已执行的迁移文件不要修改。

### 3.6 启动后端

在第一个终端执行：

```powershell
cd server
mvn -B -ntp -s .mvn/settings.xml spring-boot:run
```

后端默认地址为 `http://localhost:8080`，健康检查地址为：

```text
http://localhost:8080/actuator/health
```

### 3.7 启动 Python Agent

Agent 是独立进程，不嵌入 Java。先准备 `agent/.env.local`（不要提交），至少设置与 `server/.env.local` 相同的 `AGENT_INTERNAL_KEY`，以及 `AGENT_MODEL_API_URL`、`AGENT_MODEL_API_KEY`、`AGENT_MODEL`：

```powershell
cd agent
Copy-Item .env.example .env.local
$env:AGENT_INTERNAL_KEY = "replace-with-the-same-secret-as-server"
$env:AGENT_MODEL_API_URL = "https://your-provider/v1/chat/completions"
$env:AGENT_MODEL_API_KEY = "replace-with-secret"
$env:AGENT_MODEL = "your-model"
python -m interview_agent.server
```

如未安装开发依赖，可在 `agent` 目录执行 `python -m pip install -e ".[test]"`。Agent 默认监听 `127.0.0.1:8090`；Java 通过 `X-Agent-Key` 调用 Agent，Agent 查询资料和创建训练任务时再通过同一密钥调用 Java 的 `/internal/agent/tools`，浏览器始终只调用 Java。

### 3.8 启动前端

在第二个终端执行：

```powershell
cd web
pnpm dev
```

前端默认地址为 `http://localhost:3000`。打开该地址后使用已准备好的 Supabase 邮箱和密码登录。

### 3.9 运行质量检查

```powershell
# 前端类型检查
cd web
pnpm run lint

# 前端生产构建
pnpm run build

# 后端测试
cd ..\server
mvn -B -ntp -s .mvn/settings.xml test
```

后端测试使用 H2 和测试配置，不需要连接真实 Supabase 数据库；AI、Storage 和转写的完整联调需要配置对应服务。

### 3.10 一键启动

Windows 可双击项目根目录的 `start-dev.cmd`，或在 PowerShell 执行：

```powershell
.\start-dev.ps1
```

脚本会分别打开 Python Agent、Java 后端和 Next.js 前端终端。首次使用前先复制并填写 `agent/.env.local`；脚本不会自动生成或覆盖密钥。
