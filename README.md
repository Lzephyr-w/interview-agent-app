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

- **AI 复盘**：根据面试问题、回答和关联资料生成复盘报告、准备度、逐题建议和薄弱点标签；支持查看和删除历史复盘。
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
| 数据库迁移 | Flyway，当前迁移脚本包含 V1 至 V20、V22 至 V32 |
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
| `TENCENT_CLOUD_SECRET_ID` / `TENCENT_CLOUD_SECRET_KEY` | 腾讯云 ASR 服务端密钥 |
| `TENCENT_CLOUD_REGION` | 默认 `ap-shanghai` |
| `TENCENT_CLOUD_ASR_ENGINE_MODEL_TYPE` | 共享默认 `16k_zh`，保留 AI 语音模拟兼容性 |
| `INTERVIEW_IMPORT_ASR_ENGINE` | 仅真实导入覆盖；空值沿用共享默认，可选 `16k_zh_en_2.0` / `16k_zh_en_meeting`，不同引擎费用不同 |
| `INTERVIEW_IMPORT_ASR_HOTWORDS` | 可选 `JavaScript\|5,TypeScript\|5,Vue\|5,React\|5,Node.js\|5,Axios\|5,Base64\|5`；最多128项，每词30字符/10汉字，权重1–11；100仅16k_zh且可能强制同音替换 |
| `INTERVIEW_IMPORT_AI_TIMEOUT_SECONDS` | 导入单次 AI 超时，默认90秒；共享复盘/弱项仍60秒 |
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

不需要手工执行迁移。后端启动时 Flyway 会自动创建并使用与应用连接一致的 schema：H2 使用 `PUBLIC`，PostgreSQL 使用 JDBC URL 中的 `currentSchema`；没有该参数时才使用 `APP_DATABASE_SCHEMA` 或 `PUBLIC`。后端会执行仓库中的 V1 至 V20、V22 至 V32 迁移；已执行的迁移文件不要修改。训练任务可选保存 `source_question_id`，用于回到具体问题；删除来源后任务的文字快照仍保留。

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

## 4. 模拟 Agent 契约与职责（simulation.v1）

新建文本/语音会话的六种模型操作统一由 Java 调用 Python 内部接口 `POST /v1/agent/simulations`，使用 `X-Agent-Key` 鉴权。请求带 `version: simulation.v1`、Java 生成的 UUID `requestId`、`operation`、`deadlineAtEpochMs` 和 `input`。浏览器继续使用现有 Java API；Python 拒绝带 Origin 的模拟请求，不提供 CORS，不接收自由 Prompt、用户或资料数据库 ID。

| operation | input（均含 materials、history） | result |
| --- | --- | --- |
| VOICE_PLAN | 冻结资料 | plan：严格 10 项，每项 order/type/competency/projectName/technology/angle；firstQuestion：首题正文和匹配第一个槽位的元数据 |
| VOICE_PLAN_ONLY | 冻结资料，可选 focusCount=1或3 | plan：严格 10 项，沿用六个槽位字段，并增加 alternatives：0–2 个仅含 competency/angle 的候选；不返回题目正文或 firstQuestion |
| VOICE_QUESTION | 加 slot | questionText/type/competency/projectName/technology |
| VOICE_FEEDBACK | 加 questionText、answer | feedback |
| TEXT_MAIN_QUESTION | 历史题目 | questionText |
| TEXT_FOLLOW_UP | 加 questionText、answer | questionText |
| TEXT_FEEDBACK | 加 questionText、answer | feedback |

成功响应为 `{version, requestId, result}`；失败响应为 `{version, requestId, error: {code, message, retryable}}`。错误码仅为 INVALID_REQUEST、MODEL_TIMEOUT、MODEL_UNAVAILABLE、INVALID_MODEL_OUTPUT、UNAUTHORIZED、INTERNAL_ERROR；Java 使用本地稳定中文提示，不透传供应商异常。

- Java 是唯一业务事实来源：JWT 归属校验、资料授权和裁剪、会话/任务/时限、事务、幂等、题数顺序和最终校验。wire 上问题最多 800 字符，业务质量上问题正文最多 200 字符且最多一个问号；反馈最多两句，题目元数据最多 120 字符，计划字段不得承载问题；非法结果不落库。新会话的 PROJECT 槽位和题目必须引用冻结快照中的真实项目。文本模拟由用户选 1–10 道主问题；已回答的主问题有首道追问，首道追问标记“不确定”时可能追加第二道，追问不计入主问题数量。语音固定前 5 题基础、随后 4 题项目、最后 1 题场景或行为。
- V24 在两个会话表增加 `material_snapshot`，创建事务内冻结公司、岗位、轮次、JD（8,000 字符）、已解析简历（12,000 字符）和证据卡四字段。最多 30 张证据卡，按固定预算分摊裁剪描述/亮点/技术栈并保留项目名；超限明确报错。后续修改资料不改变本场出题或反馈的输入。
- V32 增加 `ai_mock_package_preparations` 与语音会话的 `preparation_id`。创建或更新面试包仅在保存事务内入队 `PACKAGE_VOICE_PLAN`，后台调用 `VOICE_PLAN_ONLY`；结果按实际裁剪资料的 SHA-256 指纹和规则版本复用。每个槽位有默认能力点/角度和零至两个有效候选，沿用真实项目与技术点；每场选取一个切入点，随机排列第 1–5 题和第 6–9 题，保留分布、去重和相邻字段限制，然后冻结选定计划。`/prepare` 不生成首题，`begin` 激活五十分钟计时后入队 `AI_FIRST`，使用 `VOICE_QUESTION` 生成本场首题；直接创建正式会话也会入队首题。准备中多个会话共用计划任务，完成后只为已开始会话入队首题。轮询与重试不重新抽选。JD 或证据卡独立修改后在下次准备入口核对指纹，旧包自动补充；预备会话仍十五分钟过期。规则版本已提升至 `voice-plan-only-v3`，旧缓存不会供新会话复用，已存在会话保持冻结资料和计划。知识库与文本模拟沿用原流程。
- V24 给语音会话增加 `generation_version`：历史默认 LEGACY，新建显式写 SIMULATION_AGENT_V1。只有 LEGACY 能读取旧 3 题/无计划数据；新会话始终返回 10 题并拒绝 3 项计划。无快照的历史会话继续按原授权关联查询，不回填伪快照。
- Python 的小型无状态 simulation 模块负责固定 Prompt、simulation 专用 JSON mode 和 Markdown 围栏/说明容错解析；每个请求只调用模型一次。不复用通用聊天 AgentRuntime，不调用 Java 工具、不连接数据库，不存储会话。
- 十题计划稳定性修订：完整展示十槽 JSON 示例，首次请求三个切入点，后续任务尝试使用 `focusCount=1` 只请求基础十题。计划数组、十个独立 JSON 对象、数字字符串顺序、题型大小写及缺失空技术字段可规范化；不足十题、截断或歧义 JSON、重复默认能力点、虚构项目继续拒绝。候选缺失/重复/非法只剔除候选，不拖垮有效基础计划；Java 可重新排列相同题型槽位以满足相邻限制，选项数量为零时仍随机排列并冻结。规则版本为 `voice-plan-only-v3`。2026-10-02 故障的三次日志分别为 Extra data、fields、plan；本次 Python 59 项、相关 Java 24 项通过，同份 PostgreSQL 冻结资料的新提示词真实模型请求返回有效十题。尚未重启运行服务，不代表已恢复原失败任务或达到零失败率。
- 语音出题若返回多个普通问句，会先保留带前置背景的第一个完整问句，再执行原有严格校验；含引号、代码、首问超长或括号不完整时不截断，仍由后台重试。日志只记录修复原因，不记录正文。首题格式/质量失败与下一题相同，最多三次立即重新排队；服务不可用/超时保留退避。任务 API 仅在最终 FAILED 时返回错误，PENDING/PROCESSING 不暴露上次失败；重试耗尽提示“本次内容未能生成，请重试”，错误代码保留用于诊断。
- 每次模拟 HTTP 请求预算 70 秒，Python 按统一 deadline 取消模型等待，并关闭模型 SDK 自动重试；JSON 或结构非法返回可重试错误，由现有 ai_mock_tasks 完成新的完整尝试。MODEL_TIMEOUT、MODEL_UNAVAILABLE 和 Java 业务质量拒绝均最多自动尝试 3 次，间隔 5 秒、15 秒；V24 的 available_at 防止忙轮询。手动重试复用同一任务/资源并重置尝试次数。通用聊天的 90 秒策略不变。
- 短事务在写入前锁定会话并核验任务令牌和两分钟租约；长模型调用不占数据库事务。会话过期统一转换 TIME_EXPIRED 并取消无意义任务，过期或旧 worker 的结果不可写入。处理中禁止结束保存；FAILED 可重试或结束保存已答内容；重复 finish 返回同一记录。
- V24 另增加语音题目的 ai_feedback，用于确认文本的逐题反馈；录音反馈同时保留在原音频记录中，重试复用已保存转写。无词级时间戳，不推断语速、停顿或情绪。
- 日志只记录关联 ID、操作、结果码、错误类别和耗时，不记录资料/回答/模型原文。AI 复盘、录音导入和薄弱点分析仍使用 ReviewModelClient；通用对话仍使用 /v1/agent/reply。

常规模拟使用 `VOICE_PLAN_ONLY` 只预生成计划；知识库及历史会话沿用 `VOICE_PLAN` 合并返回计划和首题。没有新增依赖或题库服务。AI 对话现支持 SSE 流式输出，刷新后重新进入会话会自动恢复未完成回复，旧的非流式接口仍保留。自动测试使用本地假模型/H2；真实模型供应商、PostgreSQL 并发和私有 Storage/转写须单独联调，不以测试通过代替外部验收。

常规模拟命中计划缓存时不再规划，但启动后仍需一次首题模型请求；候选随机化增加多样性，不保证跨场题目完全不重复，也不承诺首题即时返回。需同时重启 Java 后端和 Python Agent 才能使用新增操作；部署期间遗留的 `AI_FIRST`/历史 `AI_CREATE` 与旧计划结果仍保留兼容处理。


### 2026-10-01：真实面试录音导入当前基线

- 原始录音最大800,000,000字节，不上传Supabase；大文件/WebM由FFmpeg流式PCM落盘，16kHz单声道16bit，WAV每段约120秒、含头<=5,000,000字节。优先在末尾10秒寻找200ms短静音，没有静音用2秒重叠及真实偏移。原始重叠仍保留，分析按时间/来源去重。正常、失败、超时、中断均清理临时文件。PCM只能避免进一步损失，无法恢复原录音丢失的信息。
- 腾讯云真实导入SpeakerDiarization=1、ResTextFormat=2，不指定SpeakerNumber；保存FinalSentence/SpeakerId/StartMs/EndMs。局部声音编号为片段+SpeakerId，不能跨片段认人；语义角色INTERVIEWER/CANDIDATE/UNKNOWN与声音编号分开保存，证据不足待确认。
- INTERVIEW_IMPORT_ASR_ENGINE空值兼容共享默认，可显式16k_zh_en_2.0/16k_zh_en_meeting；不同引擎费用不同，AI语音模拟共享引擎不变。INTERVIEW_IMPORT_ASR_HOTWORDS可选技术术语，格式“词|权重”，最多128词/30字符/10汉字，权重1–11，100仅16k_zh且会强制同音替换。不自动修改本地密钥。
- 分析按发言约4500字符分块，携带未结束问题和相邻发言，用来源编号合并并引用原话。候选人反问、面试官讲解不能算候选人回答，错误回答仍是已回答。原始文本保留，手工编辑仍可用；2026-10-04 起模型另行提出有证据的词级纠错建议，不能补写或润色回答。
- 问答中的ASR来源片段使用空格拼接，不按每句强制换行。读取已有导入时，仅在文本与原系统拼接结果完全一致且有有效来源编号时整理分隔符；原始转写、片段内部段落、手工编辑和已确认问答不批量改写，无需重新ASR或AI分析。
- INTERVIEW_IMPORT_AI_TIMEOUT_SECONDS=90、INTERVIEW_IMPORT_AI_BUDGET_SECONDS=900。瞬态/429/5xx最多3次，非法JSON/业务字段最多2次，鉴权不重试；length截断缩块。有效块保存，失败部分可重试，未全部完成不READY。旧任务仅transcript也能分析；真实声音分离须重新上传原音频。
- PATCH /api/v1/interview-imports/{id}/roles 接收 {roles:[{turnId,role}]}，保存用户角色修正并清空旧分析。页面随后调用已有/analyze，只读保存文本，不请求ASR。JWT、跨用户404、目标校验、可编辑问答和确认幂等保留。
- 待确认问答旁的“重新识别问答”调用 POST /api/v1/interview-imports/{id}/analyze?force=true，从保存的转写重新分析全部问答，保留角色修正；有未保存的手工编辑时先提示确认。默认 /analyze 仍只重试失败部分。强制重分析失败时保留已有有效结果，已确认任务不重新分析；如需重新进行语音转写，使用“重新导入”上传原录音。
- V31只新增transcript_json和analysis_progress_json，不修改历史迁移。不新增队列、声纹服务或大型依赖。
- 失败任务d67e613b-69f3-4e5a-8621-846c08459fd3仅证实转写完成、问答分析失败；旧异常合并且本地没有关联日志，不能判定超时或文件格式。新诊断分类HTTP_TIMEOUT/CONNECTION/AUTHENTICATION/HTTP_429/HTTP_5XX/PROVIDER_JSON/CONTENT_MISSING/INVALID_JSON/BUSINESS_FIELDS/TRUNCATED/BUDGET。日志仅记任务/阶段/片段/引擎或模型/长度/状态/耗时/请求ID，不保存音频、转写、模型全文、JWT或凭据。
- 真实验收：用同一双人技术术语+噪声原录音、有效腾讯云及AI配置，对比前后错词/角色归属/遗漏并人工标注。FFmpeg和Mock通过不能证明识别准确率提高，旧任务文本不能代替原始录音。

本轮验证：`mvn -B -ntp -s .mvn/settings.xml test` 执行118项，99项通过、15项失败、4项错误；导入/分段/ASR参数/模型错误分类/复盘/弱项相关36项全部通过且无跳过。失败集中在SimulationWorkflowTest、AiMockQuestionAgentTest、MockInterviewControllerTest，包含现有异步worker与即时断言、模拟mock契约问题，完整后端验收未通过。前端 `npm run lint`、`node --test app/interviews/page.test.cjs`（2项）、`npm run build` 通过。FFmpeg实际生成多段WAV并验证大小、排序、偏移及成功/失败/中断清理；未执行真实双人原录音和供应商效果对比。

### 2026-10-04：本场简历辅助识别与待确认预览（第一版历史记录）

- 仅使用当前用户本场面试包关联的 READY 简历，最多取前 12000 字符，保存输入与证据快照；缺失、解析中或失败时只使用转写。简历是资料，不能补成候选人没说过的答案。
- 模型只返回角色、问答来源和可选 `corrections`。纠错包含原词的 UTF-16 区间及简历/上下文证据；服务端验证区间、短词和证据，拒绝采纳数字、否定、错位或重叠替换。建议默认未采纳，用户核对后采纳/撤销，只影响生成的预览与正式保存文本；原始转写和手工段落保留。
- 有效来源的角色、顺序或下一题边界冲突转为每题 `warnings`，列出具体编号；非法编号和结构仍有限重试后失败。页面支持原文对照、来源修改、角色修正、明确核对或排除。确认前服务端重新计算冲突，不能靠传入空 warnings 或移除来源绕过；`reviewConfirmed` 表示用户明确核对，不表示模型自动验证。
- `PATCH /api/v1/interview-imports/{id}/draft` 保存预览、`acceptedCorrectionIds` 和 `excludedQuestionIds`；确认沿用事务与幂等。`POST /api/v1/interview-imports/text` 接收 `{interviewId,transcript}`，最多40000字符，先生成 AI 预览，原空行直接导入仍保留。
- 分析缓存使用 `resume-asr-v1`、原文/人工角色/本场简历摘要及块来源编号；旧契约或资料变化只重新分析已保存的转写，不重新请求腾讯云。重分析、角色调整前提示覆盖已有手工编辑或核对状态。
- 使用已有 JSON 列，无新增迁移、依赖、Agent、向量库或长期原音频存储。每场简历 ASR 热词和真实原录音效果对照仍为后续项。

验证记录：导入分析/控制器/音频分段/模型客户端32项，ASR服务8项均通过；控制器最后补充的文本角色修改不覆盖原文检查也通过。页面8项及 TypeScript 检查通过，生产构建完成一次。最后文本来源展示调整后，桌面 Prepare 因已有3000端口服务拒绝重建，没有停止新启动的服务；桌面生产包须停服务后重新 Prepare。按用户要求停止后续检测，不执行本场真实模型重试或原录音准确率对照，也不宣称全套后端通过。

### 2026-10-04：完整话题整理与项目证据纠错（当前修订）

- 分析入口直接通读全部保存的转写生成话题稿，合并同话题的短追问、澄清和补充；保留自我介绍、候选人反问、面试官建议及业务说明。允许去语气词、重复和整理段落，保持原意、错答、数字、否定和知识不足，不从资料补标准答案。
- 同时读取当前用户本场面试包关联的 READY 简历及项目证据卡。简历最多12000字符、证据卡四字段合计最多12000字符，冻结实际输入。纠正用于待确认稿，每题 `edits` 保存原词、发言编号、证据类型/ID、短引文及推测标记；上下文推测需核对。页面展示来源原文、整理说明和依据，支持恢复本话题原文；旧词级建议仍兼容。
- 非纯口头语发言未被引用时显示 `UNASSIGNED` 待补充。同话题的问题、回答允许交错，按最早问题核对顺序；跨独立话题或角色不定提示核对。续接话题必须保留前块所有来源，不能只留下最后一句。反问/讲解保存时有角色前缀，复盘不将面试官发言评价为候选人能力。
- 缓存升级为 `topic-editor-v2`，包含原文、人工角色、本场简历及证据卡输入；旧缓存失效，只重新分析已保存文本，不重新转写。紧凑输入约16000字符分块，单次输出12288 tokens；截断有限重试后缩块，保存有效块，重试恢复缩块边界。默认 `INTERVIEW_IMPORT_AI_TIMEOUT_SECONDS=180`，总预算900秒，显式环境值仍优先；用户已有 `.env.local` 未修改。供应商 HTTP 408 仍按原错误分类处理。
- 新增 `kind/notes/edits`，原始转写与手工修改保留；任务、草稿、确认幂等和用户隔离沿用现有流程，无新增依赖、迁移或 Agent。已有READY任务点击“重新识别问答”生成新稿，已确认记录不自动重写。

本次修订只修改代码、文档及可运行检查；按用户要求没有运行测试、类型检查、编译、构建、重启或真实模型调用，以上第一版通过记录不能代表本次通过。运行新代码后由用户对照原文检验完整性、误改与话题合并效果；桌面生产包须用户退出App/服务后重新Prepare再启动。

### 2026-10-04：纠错元数据不匹配不再中断整场整理

任务 `1f56fa30-a29a-40f2-b8c4-0c0fd2b01e84` 的最后一次模型调用已HTTP 200成功，随后因单条纠错与来源/展示词不匹配而被后端拒绝；页面保留的40条是旧稿。原实现将可恢复的单条纠错问题当作整场业务错误，并重复请求整份模型结果。

- 纠错编号不对时，仅在本话题引用来源内查找原词的唯一精确匹配并校正编号，不模糊匹配、不借用其他话题。未用于最终稿的中间纠错记录忽略并提示核对。
- 原词、证据或受保护内容无法验证时，拒绝该话题的自动改写，恢复原文摘录，添加 `EDIT_VALIDATION_FAILED` 和具体整理说明；其他有效话题及来源保留。不通过单纯删除审计记录来保留不可靠替换。
- 问答来源编号和整体JSON结构仍严格校验；纠错待确认在草稿保存与最终确认时保留，不能靠客户端清空warnings绕过。日志新增任务/块/话题/纠错编号及静态原因，不记录原词、资料或模型全文。

补充了可运行回归检查，按用户要求未运行测试、构建、重启或真实模型请求。更新运行版本后使用已保存转写重新识别，不需重新上传原音频。
