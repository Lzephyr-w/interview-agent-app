# AI 简历助手

AI 简历助手是面向求职准备的前后端分离应用，围绕简历、岗位 JD、项目证据和面试记录构建可追溯的准备闭环。它提供 AI 文本与语音模拟、录音转写、智能复盘、薄弱点训练及 AI Agent 对话，帮助用户完成从资料整理、模拟练习到复盘改进的全流程准备。
<img width="2880" height="1325" alt="p1" src="https://github.com/user-attachments/assets/9a0ff783-77cd-4baa-9205-36b79a26ef7c" />
<img width="2880" height="1325" alt="p3" src="https://github.com/user-attachments/assets/9cd38888-e08c-4738-9b36-7853220744d7" />
<img width="2880" height="1325" alt="p2" src="https://github.com/user-attachments/assets/96ab3e2f-2509-449b-a48b-d69af37a2f54" />


## 1. 项目简介与核心功能

### 已实现功能

- **账户与权限**：使用 Supabase Auth 邮箱/密码登录；前端携带 JWT，后端校验 JWT 并按当前用户隔离数据。
- **首页仪表盘**：展示资料、面试包、待复盘、训练任务等概览，汇总近期活动和薄弱点，并支持维护冲刺清单。
- **资料库**：上传、预览、下载和删除 PDF、DOC、DOCX 简历文件；服务端提取简历文本。支持管理岗位 JD、项目证据卡和面试包，并将资料组合到一次面试中。
- **真实面试记录**：创建、编辑和删除面试；维护问题、回答和自评；支持粘贴转写文本按空行分段；支持上传录音、语音转写、AI 识别问答后检查并加入面试记录。
- **AI 复盘**：根据面试问题、回答和关联资料生成复盘报告、准备度、逐题建议和薄弱点标签；支持查看和删除历史复盘。
- **AI 文本模拟**：基于面试包资料生成 4 道主问题；回答主问题后最多生成 1 道追问，支持跳过、逐题 AI 反馈，并在完成后保存为正式面试记录。
- **AI 录音模拟**：进行 10 道题的录音模拟，每题限时 5 分钟；支持浏览器录音、语音转写、回答确认和逐题反馈，完成后可形成正式面试记录。
- **薄弱点与训练任务**：用户主动发起 AI 汇总分析，结合当前面试问答、每场最新逐题复盘和关联简历生成最多 3 个具体薄弱点；每项可追溯到具体题目，并可据此创建、编辑和删除训练任务。分析结果按用户保存为快照，数据发生变化后会标记为过期；刷新或 GET 请求不会自动调用模型。
- **AI 对话**：创建带可选面试包、面试、复盘或薄弱点上下文的会话；保存历史消息，调用 AI 回复，并支持删除会话。
- **私有文件存储**：简历文件和录音通过服务端访问 Supabase 私有 Storage；浏览器不接触 Service Role Key。

### 当前边界

- AI 录音模拟固定为 10 道题、每题 5 分钟；单题录音不超过 10 MiB。
- 真实面试录音导入支持 WebM、Ogg、MP3、MP4/M4A、WAV，单文件不超过 25 MiB。
- 简历上传支持 PDF、DOC、DOCX，单文件不超过 10 MiB；扫描件或受保护文件可能无法提取正文。
- 薄弱点分析只在用户点击“开始 AI 分析 / 重新分析”时调用一次模型；当前面试、问题、最新复盘或关联简历变化后，旧快照会隐藏并提示重新分析。
- 本项目仅覆盖本地开发启动，不包含生产部署配置。

## 2. 技术栈与环境要求

| 模块 | 技术与版本 |
| --- | --- |
| 前端 | Next.js 15.2.4、React 19.0.0、TypeScript 5.8.2 |
| 后端 | Java 21、Spring Boot 3.4.3、Spring Security、Spring JDBC |
| 数据库 | PostgreSQL / Supabase PostgreSQL；未配置数据库连接时默认使用 H2 内存数据库 |
| 数据库迁移 | Flyway，当前迁移脚本包含 V1 至 V20、V22 |
| 文件解析 | Apache PDFBox 3.0.8、Apache POI 5.5.1 |
| 认证与存储 | Supabase Auth、Supabase 私有 Storage |
| AI | LangChain 单 Agent + OpenAI 兼容 Chat Completions API；OpenAI 兼容音频转写 API 或火山引擎音频转写 |
| 测试 | JUnit 5、Spring Boot Test、Spring Security Test、H2 |

最低环境：Node.js 20+、pnpm 9+、JDK 21+、Maven 3.9+、Python 3.10+、Git。

需要一个 Supabase 项目用于 Auth；使用简历上传或录音功能时还需要私有 Storage bucket。后端默认使用 H2 内存数据库，因此最小本地启动不要求另外安装 PostgreSQL；重启后 H2 数据会丢失。需要持久化数据时配置 Supabase PostgreSQL。

## 3. 本地启动与运行指南

### 3.1 拉取代码

```powershell
git clone https://github.com/Lzephyr-w/interview-agent-app.git
cd interview-agent-app
```

### 3.2 准备 Supabase

本项目将 Supabase Auth、Supabase Storage 和业务数据库分开使用：邮箱/密码登录由 Supabase Auth 负责，业务数据可以使用本地 H2 或 Supabase PostgreSQL。

1. 创建 Supabase 项目。在 Authentication → Providers → Email 中启用 Email provider 和 Confirm email；在 Authentication → URL Configuration 中将 Site URL 设为 `http://localhost:3000`，并将 `http://localhost:3000/**` 加入 Redirect URLs（部署时替换为实际前端地址）。用户可在登录页点击“注册账号”创建邮箱/密码账号，完成验证邮件后再登录。
2. 在 Project Settings → API 中获取 Project URL 和 anon/publishable key，分别填写到 `web/.env.local` 和 `server/.env.local`。anon/publishable key 可以出现在前端，Service Role Key 不可以。
3. 如果要使用文件上传或录音功能，创建以下私有 Storage bucket，保持 Public 关闭：

   - `resume-files`：简历原文件
   - `ai-mock-audio`：AI 录音模拟和真实面试录音导入文件

### 3.3 创建环境配置

在项目目录执行：

```powershell
Copy-Item web/.env.example web/.env.local
Copy-Item server/.env.example server/.env.local
Copy-Item agent/.env.example agent/.env.local
```

分别编辑三个配置文件。不要把真实密钥、数据库密码或 `.env.local` 文件提交到 Git。

#### `web/.env.local`

| 变量 | 说明 |
| --- | --- |
| `NEXT_PUBLIC_API_BASE_URL` | Java 后端地址，默认 `http://localhost:8080` |
| `NEXT_PUBLIC_SUPABASE_URL` | Supabase Project URL |
| `NEXT_PUBLIC_SUPABASE_ANON_KEY` | Supabase 公共 anon/publishable key，不能填 Service Role Key |

最小配置示例：

```env
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
NEXT_PUBLIC_SUPABASE_URL=https://your-project-ref.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=your-anon-or-publishable-key
```

修改后需要重启前端开发服务器。

#### `server/.env.local`

| 变量 | 说明 |
| --- | --- |
| `APP_CORS_ALLOWED_ORIGIN` | 前端地址，默认 `http://localhost:3000` |
| `SUPABASE_URL` | Supabase Project URL，必填 |
| `SUPABASE_STORAGE_URL` | 通常为 `${SUPABASE_URL}/storage/v1` |
| `SUPABASE_STORAGE_SERVICE_KEY` | 服务端访问私有 bucket 的 Service Role Key，不能提交到 Git |
| `SUPABASE_RESUME_FILES_BUCKET` | 简历文件 bucket，默认 `resume-files` |
| `SUPABASE_AI_MOCK_AUDIO_BUCKET` | AI 模拟和面试录音 bucket，默认 `ai-mock-audio` |
| `AI_REVIEW_API_URL` | OpenAI 兼容 Chat Completions 地址；AI 复盘和模拟功能需要 |
| `AI_REVIEW_API_KEY` | AI 模型服务端密钥 |
| `AI_REVIEW_MODEL` | AI 模型名 |
| `AI_TRANSCRIPTION_API_URL` | OpenAI 兼容音频转写地址，使用该方式时填写 |
| `AI_TRANSCRIPTION_API_KEY` | 音频转写服务密钥 |
| `AI_TRANSCRIPTION_MODEL` | 音频转写模型名 |
| `VOLCENGINE_SPEECH_API_KEY` | 可选的火山引擎转写密钥，与 OpenAI 兼容转写二选一 |
| `AGENT_SERVICE_URL` | Python Agent 地址，默认 `http://localhost:8090` |
| `AGENT_INTERNAL_KEY` | Java 与 Python Agent 之间的共享密钥，必须与 `agent/.env.local` 相同 |

最小 H2 配置示例：

```env
APP_CORS_ALLOWED_ORIGIN=http://localhost:3000
SUPABASE_URL=https://your-project-ref.supabase.co

# H2 模式下不要填写这三项；删除或注释 server/.env.example 中对应的行。
# SPRING_DATASOURCE_URL=...
# SPRING_DATASOURCE_USERNAME=...
# SPRING_DATASOURCE_PASSWORD=...
```

`SUPABASE_STORAGE_SERVICE_KEY` 只在文件上传、下载或录音功能中需要；AI 和转写变量只在调用对应功能时需要。未使用的可选变量可以留空，但不能原样保留 `replace-with-*` 占位值。

默认可将 `SUPABASE_JWT_SECRET`、`SUPABASE_JWT_JWK_KID`、`SUPABASE_JWT_JWK_X`、`SUPABASE_JWT_JWK_Y` 留空，后端通过 Supabase JWKS 校验 JWT；Legacy HS256 或网络受限时再填写对应项。示例中的 `replace-with-*` 不能原样保留。

数据库配置二选一：

- **H2 内存数据库**：从 `server/.env.local` 删除或注释 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 三行，使用 `application.yml` 默认值。
- **Supabase PostgreSQL**：填写上述三个变量，JDBC URL 建议包含 `sslmode=require&currentSchema=interview_agent`。

H2 数据只存在 Java 进程内存中，后端重启后业务数据会丢失；Supabase Auth 中的登录账号不会丢失。邮箱登录不依赖 PostgreSQL 配置，但仍需要 `web/.env.local` 中的 Supabase URL/anon key，以及 `server/.env.local` 中的 `SUPABASE_URL`。

#### `agent/.env.local`

| 变量 | 说明 |
| --- | --- |
| `AGENT_INTERNAL_KEY` | 与 `server/.env.local` 中的值完全相同，用于内部鉴权 |
| `AGENT_HOST` | Agent 监听地址，本机保持 `127.0.0.1` |
| `AGENT_PORT` | Agent 端口，默认 `8090` |
| `JAVA_AGENT_TOOL_URL` | Java Agent 工具接口，默认 `http://localhost:8080/internal/agent/tools` |
| `AGENT_MODEL_API_URL` | OpenAI 兼容 Chat Completions 地址 |
| `AGENT_MODEL_API_KEY` | Agent 使用的模型服务密钥 |
| `AGENT_MODEL` | Agent 使用的模型名 |

示例：

```env
AGENT_INTERNAL_KEY=use-the-same-random-secret-as-server
AGENT_HOST=127.0.0.1
AGENT_PORT=8090
JAVA_AGENT_TOOL_URL=http://localhost:8080/internal/agent/tools
AGENT_MODEL_API_URL=https://your-provider/v1/chat/completions
AGENT_MODEL_API_KEY=your-agent-model-key
AGENT_MODEL=your-model-name
```

Agent 的模型配置只在使用 AI 对话等 Agent 功能时需要；`AGENT_INTERNAL_KEY` 必须和 Java 后端配置一致。

不要提交 `server/.env.local`、`web/.env.local`、`agent/.env.local`、Service Role Key、数据库密码或模型密钥。

### 3.4 安装前端依赖

```powershell
cd web
pnpm install
```

如需使用 Python Agent，在 `agent` 目录安装依赖：

```powershell
cd ..\agent
python -m pip install -e ".[test]"
```

### 3.5 初始化数据库

不需要手工执行迁移。后端启动时 Flyway 会自动创建 `interview_agent` schema，并执行仓库中的 V1 至 V20、V22 迁移；已执行的迁移文件不要修改。训练任务可选保存 `source_question_id`，用于回到具体问题；删除来源后任务的文字快照仍保留。

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

Agent 是独立进程，不嵌入 Java。先按上面的说明准备 `agent/.env.local`，其中 `AGENT_INTERNAL_KEY` 必须与 `server/.env.local` 相同：

```powershell
cd agent
python -m interview_agent.server
```

该安装命令会安装 Python 3.10+ 所需的 LangChain、`langchain-openai` 和测试依赖。Agent 默认监听 `127.0.0.1:8090`；Java 通过 `X-Agent-Key` 调用 Agent，Agent 查询资料和创建训练任务时再通过同一密钥调用 Java 的 `/internal/agent/tools`，浏览器始终只调用 Java。

### 3.8 启动前端

在第二个终端执行：

```powershell
cd web
pnpm dev
```

前端默认地址为 `http://localhost:3000`。打开该地址后可使用已有 Supabase 邮箱和密码登录，或在登录页注册并完成邮箱验证后登录。

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

# Python Agent 测试
cd ..\agent
py -3.10 -m pytest
```

后端测试使用 H2 和测试配置，不需要连接真实 Supabase 数据库；AI、Storage 和转写的完整联调需要配置对应服务。

### 3.10 一键启动

Windows 可双击项目根目录的 `start-dev.cmd`，或在 PowerShell 执行：

```powershell
.\start-dev.ps1
```

脚本会分别打开 Python Agent、Java 后端和 Next.js 前端终端。首次使用前先复制并填写 `agent/.env.local`；脚本不会自动生成或覆盖密钥。
