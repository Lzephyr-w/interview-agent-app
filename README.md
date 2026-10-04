# 智面 · AI 面试训练平台

智面 · AI 面试训练平台是面向求职准备的前后端分离应用，围绕简历、岗位 JD、项目证据和面试记录构建可追溯的准备闭环。它提供 AI 文本与语音模拟、录音转写、智能复盘、薄弱点训练及 AI Agent 对话，帮助用户完成从资料整理、模拟练习到复盘改进的全流程准备。

## 1. 项目简介与核心功能

### 已实现功能

- **账户与权限**：使用 Supabase Auth 邮箱/密码登录；前端携带 JWT，后端校验 JWT 并按当前用户隔离数据。
- **首页仪表盘**：展示资料、面试包、待复盘、训练任务等概览，汇总近期活动和薄弱点，并支持维护冲刺清单。
  <img width="2560" height="1185" alt="首页" src="https://github.com/user-attachments/assets/fe442dd4-cdb7-4ac1-9786-09c1b4f5c5a6" />

- **资料库**：上传、预览、下载和删除 PDF、DOC、DOCX 简历文件；服务端提取简历文本。支持管理岗位 JD、项目证据卡和面试包，并将资料组合到一次面试中。项目证据卡统一记录项目名称、技术栈、项目描述与职责、项目亮点；未填写内容显示“待补充”。
- **真实面试记录**：创建、编辑和删除面试；维护问题、回答和自评；支持粘贴转写文本按空行分段；支持上传录音、语音转写、AI 识别问答后检查并加入面试记录。
  <img width="2560" height="1184" alt="image" src="https://github.com/user-attachments/assets/7e963210-2eb8-4bf5-8221-b36b5ba54166" />

- **AI 复盘**：以本场已确认问答生成整场表现总结和逐题改进建议，关联资料仅辅助理解；支持查看和删除历史复盘。准备度仅保留接口兼容，页面不作为主要结论。
  <img width="2560" height="1181" alt="image" src="https://github.com/user-attachments/assets/a0142222-e27c-4305-aa2c-f1495e67cd24" />

- **AI 文本模拟**：开始时可选择 1–10 道主问题（旧请求默认 4 道）；每道已回答的主问题可能有 1–2 道追问，追问不计入主问题数量。支持跳过、逐题 AI 反馈，并在完成后保存为正式面试记录。
  <img width="2560" height="1185" alt="文本模拟" src="https://github.com/user-attachments/assets/d5dc0809-f1b8-43ad-b83b-6ccc83cb109d" />

- **知识库模拟**：在资料库按类别导入 MD、XLSX、DOC、DOCX；文本与语音模拟都可选择一个或多个类别，逐题检索文档片段并显示来源。仍需面试包提供岗位与真实经历背景。

- **AI 录音模拟**：进行 10 道题的录音模拟，每题限时 5 分钟；常规模拟在创建或更新面试包后异步准备十题计划，每个槽位提供三个切入点，每场随机选取并调整同题型顺序，开始后单独生成首题；支持浏览器录音、语音转写、回答确认和逐题反馈，完成后可形成正式面试记录。
  <img width="2560" height="1186" alt="语音模拟面试" src="https://github.com/user-attachments/assets/06712822-436c-40d7-bfb5-08fc738cedb0" />

- **薄弱点与训练任务**：用户主动发起 AI 汇总分析，结合当前面试问答、每场最新逐题复盘和关联简历生成最多 3 个具体薄弱点；每项可追溯到具体题目，并可据此创建、编辑和删除训练任务。分析结果按用户保存为快照，数据发生变化后会标记为过期；刷新或 GET 请求不会自动调用模型。
   <img width="2560" height="1184" alt="薄弱点" src="https://github.com/user-attachments/assets/27707cb0-7505-4e8b-ada8-5d17aa13ee2f" />

- **AI 对话**：创建带可选面试包、面试、复盘或薄弱点上下文的会话；保存历史消息，调用 AI 回复，并支持删除会话。
   <img width="2560" height="1184" alt="ai对话3" src="https://github.com/user-attachments/assets/a2d670cb-8f68-447e-84e8-6ca2902b97a3" />

- **私有文件存储**：简历文件和 AI 模拟录音通过服务端访问 Supabase 私有 Storage；真实面试录音仅在服务端临时保存用于识别，完成后删除。

### 当前边界

- AI 录音模拟固定为 10 道题、每题 5 分钟；单题录音不超过 10 MiB。
- 真实面试录音导入支持 WebM、Ogg、MP3、MP4/M4A、WAV，单文件不超过 800 MB；超过 5 MB 的音频会由服务器 FFmpeg 转码、切片后逐段识别，临时文件在处理后删除。服务器需安装 FFmpeg；录音不保存到 Supabase。
- 简历上传支持 PDF、DOC、DOCX，单文件不超过 10 MiB；扫描件或受保护文件可能无法提取正文。
- 薄弱点分析只在用户点击“开始 AI 分析 / 重新分析”时调用一次模型；当前面试、问题、最新复盘或关联简历变化后，旧快照会隐藏并提示重新分析。
- 本项目提供本地开发启动和个人 Windows 桌面生产运行入口，不包含公共服务器部署配置。

## 2. 技术栈与环境要求

| 模块 | 技术与版本 |
| --- | --- |
| 前端 | Next.js 15.2.4、React 19.0.0、TypeScript 5.8.2 |
| 后端 | Java 21、Spring Boot 3.4.3、Spring Security、Spring JDBC |
| 数据库 | PostgreSQL / Supabase PostgreSQL；未配置数据库连接时默认使用 H2 内存数据库 |
| 数据库迁移 | Flyway，当前迁移脚本包含 V1 至 V20、V22 至 V33（V21 保留缺号） |
| 文件解析 | Apache PDFBox 3.0.8、Apache POI 5.5.1 |
| 认证与存储 | Supabase Auth、Supabase 私有 Storage |
| AI | LangChain 单 Agent + OpenAI 兼容 Chat Completions API；腾讯云录音文件识别 API |
| 测试 | JUnit 5、Spring Boot Test、Spring Security Test、H2 |

最低环境：Node.js 20+、pnpm 9+、JDK 21+、Maven 3.9+、Python 3.10+、Git。

需要一个 Supabase 项目用于 Auth；使用简历或 AI 模拟录音功能时还需要私有 Storage bucket。真实面试录音导入不保存录音文件。后端默认使用 H2 内存数据库，因此最小本地启动不要求另外安装 PostgreSQL；重启后 H2 数据会丢失。需要持久化数据时配置 Supabase PostgreSQL。

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
   - `ai-mock-audio`：AI 录音模拟文件

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
| `FFMPEG_PATH` | FFmpeg 可执行文件路径，默认从 `PATH` 查找；处理大于 5 MB 的录音时需要 |
| `SUPABASE_URL` | Supabase Project URL，必填 |
| `SUPABASE_STORAGE_URL` | 通常为 `${SUPABASE_URL}/storage/v1` |
| `SUPABASE_STORAGE_SERVICE_KEY` | 服务端访问私有 bucket 的 Service Role Key，不能提交到 Git |
| `SUPABASE_RESUME_FILES_BUCKET` | 简历文件 bucket，默认 `resume-files` |
| `SUPABASE_AI_MOCK_AUDIO_BUCKET` | AI 模拟录音 bucket（真实面试导入不使用），默认 `ai-mock-audio` |
| `AI_REVIEW_API_URL` | OpenAI 兼容 Chat Completions 地址；AI 复盘、录音导入和薄弱点分析需要 |
| `AI_REVIEW_API_KEY` | AI 模型服务端密钥 |
| `AI_REVIEW_MODEL` | AI 模型名 |
| `AI_REVIEW_TIMEOUT_SECONDS` | 仅复盘单次请求超时，默认240秒；每次还受整场剩余时间限制，输出8192 tokens |
| `TENCENT_CLOUD_SECRET_ID` / `TENCENT_CLOUD_SECRET_KEY` | 腾讯云 ASR 服务端密钥 |
| `TENCENT_CLOUD_REGION` | 默认 `ap-shanghai` |
| `TENCENT_CLOUD_ASR_ENGINE_MODEL_TYPE` | 共享默认 `16k_zh`，保留 AI 语音模拟兼容性 |
| `INTERVIEW_IMPORT_ASR_ENGINE` | 仅真实导入覆盖；空值沿用共享默认，可选 `16k_zh_en_2.0` / `16k_zh_en_meeting`，不同引擎费用不同 |
| `INTERVIEW_IMPORT_ASR_HOTWORDS` | 可选 `JavaScript\|5,TypeScript\|5,Vue\|5,React\|5,Node.js\|5,Axios\|5,Base64\|5`；最多128项，每词30字符/10汉字，权重1–11；100仅16k_zh且可能强制同音替换 |
| `INTERVIEW_IMPORT_AI_TIMEOUT_SECONDS` | 录音整理单次 AI 超时，默认180秒；弱项通用 JSON 调用仍60秒，复盘独立默认240秒 |
| `INTERVIEW_IMPORT_AI_BUDGET_SECONDS` | 导入分析总预算，默认900秒；超预算保留有效结果 |
| `AGENT_SERVICE_URL` | Python Agent 地址，默认 `http://localhost:8090` |
| `AGENT_INTERNAL_KEY` | Java 与 Python Agent 之间的共享密钥，必须与 `agent/.env.local` 相同 |

最小 H2 配置示例：

```env
APP_CORS_ALLOWED_ORIGIN=http://localhost:3000
SUPABASE_URL=https://your-project-ref.supabase.co

# H2 模式下不要填写下面三项；删除或注释 server/.env.example 中对应的行。
# H2 自动使用 PUBLIC schema，无需设置 APP_DATABASE_SCHEMA。
# SPRING_DATASOURCE_URL=...
# SPRING_DATASOURCE_USERNAME=...
# SPRING_DATASOURCE_PASSWORD=...
```

`SUPABASE_STORAGE_SERVICE_KEY` 只在文件上传、下载或录音功能中需要；AI 和转写变量只在调用对应功能时需要。未使用的可选变量可以留空，但不能原样保留 `replace-with-*` 占位值。

默认可将 `SUPABASE_JWT_SECRET`、`SUPABASE_JWT_JWK_KID`、`SUPABASE_JWT_JWK_X`、`SUPABASE_JWT_JWK_Y` 留空，后端通过 Supabase JWKS 校验 JWT；Legacy HS256 或网络受限时再填写对应项。示例中的 `replace-with-*` 不能原样保留。

数据库配置二选一：

- **H2 内存数据库**：从 `server/.env.local` 删除或注释 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 三行，后端自动使用 `PUBLIC` schema。
- **Supabase PostgreSQL**：填写数据库变量；后端自动读取 JDBC URL 中的 `currentSchema`。如果 URL 没有该参数，再设置 `APP_DATABASE_SCHEMA` 作为覆盖值。

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

Agent 的模型配置用于 AI 对话及文本/语音模拟的出题、追问和反馈；`AGENT_INTERNAL_KEY` 必须和 Java 后端配置一致。

不要提交 `server/.env.local`、`web/.env.local`、`agent/.env.local`、Service Role Key、数据库密码或模型密钥。

### 3.4 安装前端依赖

```powershell
cd web
pnpm install
```

如需使用 Python Agent，在 `agent` 目录安装依赖：

```powershell
cd ..\agent
py -3.10 -m pip install -e ".[test]"
```

### 3.5 初始化数据库

不需要手工执行迁移。后端启动时 Flyway 会自动创建并使用与应用连接一致的 schema：H2 使用 `PUBLIC`，PostgreSQL 使用 JDBC URL 中的 `currentSchema`；没有该参数时才使用 `APP_DATABASE_SCHEMA` 或 `PUBLIC`。后端会执行仓库中的 V1 至 V20、V22 至 V33 迁移；已执行的迁移文件不要修改。训练任务可选保存 `source_question_id`，用于回到具体问题；删除来源后任务的文字快照仍保留。

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

修改 `agent/src` 中的模拟契约或提示词后，需要重启此 Python 进程；只重启 Java 后端不会加载 Agent 的新代码。

```powershell
cd agent
py -3.10 -m interview_agent.server
```

该安装命令会安装 Python 3.10+ 所需的 LangChain、`langchain-openai` 和测试依赖。Agent 默认监听 `127.0.0.1:8090`；Java 通过 `X-Agent-Key` 调用 Agent，仅通用对话 Agent 查询资料和创建训练任务时才通过同一密钥调用 Java 的 `/internal/agent/tools`，浏览器始终只调用 Java。

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

脚本在启动任何服务前验证 Python：存在 `agent/.venv` 时必须使用其中的 Python 3.10+，虚拟环境损坏或版本过低会直接报错；没有虚拟环境时显式使用 `py -3.10`，不回退到 PATH 中不确定版本的 `python`。验证通过后在后台启动 Python Agent、Java 后端和 Next.js 前端。首次使用前先复制并填写 `agent/.env.local`；脚本不会自动生成或覆盖密钥。

### 3.11 个人 Windows 桌面入口

沿用三份 `.env.local`，业务数据库必须配置 PostgreSQL；桌面模式拒绝回退到 H2。首次使用或更新代码、前端公开配置后，在项目根目录执行：

```powershell
Push-Location .\desktop
npm.cmd ci --ignore-scripts=false
Pop-Location
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop.ps1 -Action Prepare
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop.ps1 -Action Install
```

首次安装 Electron 窗口运行时需要 Node.js 22.12+ 和网络；日常打开不会安装依赖。`Prepare` 执行前端类型检查/生产构建及 Java JAR 打包（跳过 Java 测试，不代表完整后端测试通过）。准备前请关闭 App，并停止占用 3000、8080、8090 的服务。`Install` 创建带项目机器人图标的“智面”快捷方式，并移除本项目旧的“停止智面”。

以后双击“智面”会打开独立 App 窗口，隐藏启动三个服务，就绪后加载页面；重复双击聚焦同一个窗口。**关闭主窗口会自动停止 Next.js、Java、Python 及其子进程**，无需单独停止入口；启动途中关闭也会取消启动并清理。关闭文件预览窗口不退出主 App。请先保存录音和编辑内容，进行中的任务会被中断。服务仅绑定回环地址，登录、数据和 AI 仍需联网。Electron 使用独立的持久化用户目录 `%APPDATA%\Zhimian`，首次使用需要重新登录。

命令行检查或不打开窗口时可使用：

```powershell
.\desktop.ps1 -Action Start -NoWindow -NoDialog
.\desktop.ps1 -Action Status
.\desktop.ps1 -Action Stop -NoDialog
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop.test.ps1
node .\desktop\main.test.cjs
```

`-NoWindow` 是命令行服务调试模式，需要配对 `Stop`；桌面 App 自动管理退出。如果用任务管理器强制结束 App 主进程，退出钩子无法执行，保留 `Stop` 命令用于恢复。构建过时、配置缺失或陌生端口占用会报错，不会自动换端口或终止开发进程。日志、构建指纹和进程状态保存在被 Git 忽略的 `runtime-logs/desktop-*`；密钥不会写进快捷方式。详细调研和验收记录仅保存在本机 `docs/`，不随 Git 提交。
