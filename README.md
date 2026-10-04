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

## 4. 开发与验收记录

模拟 Agent 的 `simulation.v1` 契约、职责边界和历史实施记录已集中放在 [`docs/后续优化功能.md`](../docs/后续优化功能.md)；README 只保留当前功能、运行方式和按日期保留的项目记录，避免把内部协议混入入门文档。


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

### 2026-10-04：同批话题来源重叠不再误判为跨块续接

- 合并只匹配本次回复之前的旧话题；同一回复内来源重叠的条目分别保留，不覆盖前条，不因“续接话题遗漏上一块来源”重试整份模型结果。
- 一个旧话题在同一回复中只更新一次；存在多个旧话题匹配时保留各稿供核对。来源重叠重新计算为 `SOURCE_OVERLAP`，生成不同条目标识，支持预览编辑、明确核对或排除；客户端清空 warnings 不能绕过确认。
- 唯一可识别的跨块续接仍必须保留旧来源。复用现有原文、缓存、草稿接口和页面提示，不新增依赖或迁移。

已补充合并与草稿确认的可运行回归检查，按用户要求未运行测试、构建、服务重启或真实模型请求。本轮改动保留未提交状态；用户更新运行版本后，在失败预览点击“重新识别”即可复用保存转写。

### 2026-10-04：按参考稿生成自然问答（readable-qa-v3，基础规则）

用户提供的READY结果有15个实际话题，其中10条因纠错元数据验证失败退回了原文拼接，另有89条来源被列成遗漏；宽泛话题合并也吞掉了独立问答。新规则按参考稿生成简洁自然的问题、完整分段回答和少量方括号不确定说明；短确认合入原问答，回答目标变化的追问单列，不固定题数。

- 名称和技术词由模型结合本场READY简历、关联项目证据卡及语境直接整理。不再要求逐词纠错引文、逐发言roles或问答编号数组；每条仅带两个起止编号的 `sourceSpan` 供折叠原文对照。新稿不经过旧词级校验，也不会因此整条退回原文。数字、否定、错答和知识不足仍要求保持原意，不能从资料补答。
- 仅允许声明寒暄、纯口头语、重复的省略范围；未覆盖的其他原文按连续范围保留成折叠核对项，不堆出大串编号。来源越界、结构和长度仍验证。范围只是阅读定位，不能机械证明名称纠正、回答归属或内容完整；加入前需用户统一核对。未整理占位项需补充或明确排除。
- 页面只展示直接可编辑的问答输入框，移动/排除按钮放在卡片右上角，原文对照折叠，必要说明用方括号；来源范围不在正文展示。原始转写和手工段落保留，草稿范围不能被客户端删改或伪造。
- 新分析缓存使用 `readable-qa-v3`，旧缓存仅在重新分析时失效。已有 `topic-editor-v2` 草稿保持可读、可编辑、可确认；用户更新运行版本后点击“重新识别问答”才生成新稿，不修改已确认记录。无新增依赖、迁移或额外模型步骤。

已留下新契约、来源验证、遗漏、草稿确认和页面阅读形式的可运行检查；按用户要求未执行测试、类型检查、编译、构建、重启或真实模型请求，实际效果由用户验收。改动保持未提交、未推送。

### 2026-10-04：修复已整理内容的来源漏标（readable-qa-v4，当前规则）

自我介绍后半段已写进回答，但模型的sourceSpan只标了前半段，后端因此生成重复的“未整理原文”提示。新整理提示要求范围覆盖完整介绍；出现未关联原文时，在现有时间预算内最多追加一次仅核对来源的模型请求。确认内容已完整表达且属于同一问答后，只扩展对应范围，不重写回答或新增问题。补丁不能缩小旧范围、越界、跨过其他问答或重复关联；真正遗漏或归属不确定的内容继续保留，核对失败也保留原稿和提示。

有效补丁随块结果保存，缓存改为readable-qa-v4，已有v3/v2草稿继续可读、可修改和确认。用户更新后端运行版本后点击“重新识别问答”，复用保存转写，无需重新上传。已补来源修复、真实遗漏、非法补丁、核对超时与草稿保存的可运行检查，未执行测试/构建/重启/真实请求；代码未提交、未推送。

### 2026-10-04：草稿与角色PATCH跨域请求

`/{id}/draft`、`/{id}/roles`使用PATCH，但全局CORS方法白名单漏了PATCH，浏览器预检失败，实际保存请求不能进入接口。已在SecurityConfig补上PATCH，沿用原有来源、请求头和鉴权限制；补充两个接口的预检及非法来源检查，未运行测试/构建/重启，未提交。用户更新并重启后端后重试草稿保存即可，无需重新识别问答。

### 2026-10-04：未确认的录音导入持续展示

未确认导入不再依赖sessionStorage中的任务ID。问答页通过GET `/api/v1/interview-imports/pending?interviewId=...`读取当前用户、本场面试的最新导入，无时间过期条件；刷新、关闭重开或重新登录后可恢复后端已保存的转写、问答草稿和失败状态。恢复中的处理任务每5秒读取进度，完成后显示结果，不重新上传或调用AI。

导入结果放在添加方式切换区域之外，切换录音/文本/手动不会隐藏；移除“结束空预览”，只有确认提交成功后清除，提交失败仍展示。查询先取最新任务再判断SAVED，确认后不会把更早的废弃草稿重新显示。显式重新导入以新任务为当前预览，历史任务保留；原音频临时文件仍按既有处理规则清理。已留下持久恢复、用户隔离、处理进度与确认成功/失败的可运行检查，未执行测试、构建、重启或真实请求，未提交、未推送。

### 2026-10-04：恢复可读问答流程的角色推断（readable-qa-v5）

v3/v4只生成可读问答，未回填逐条发言角色，导致原文面板持续显示UNKNOWN/待确认。现有整理请求同时返回精简的speakerRoles（片段内说话人映射）和roleSpans（文本及局部例外的发言范围），解析后更新并保存原文角色；不增加独立AI请求，不将声音0/1跨片段固定映射。人工修正优先，冲突保持UNKNOWN；非法或越界角色元数据忽略并记录数量，已有效的问答不因此整批失败。

缓存升级readable-qa-v5，v4/v3/v2草稿仍可读、修改和确认。更新并重启后端后点击“重新识别问答”回填已有录音的角色，复用保存转写，无需重新上传；页面区分“推断角色”和“角色待确认”。已留下片段隔离、文本/混合发言、人工优先、冲突与非法元数据、数据库读取及缓存恢复的可运行检查；未执行测试、构建、重启或真实请求，未提交、未推送。

### 2026-10-04：整理稿姓名校正（readable-qa-v6）

从本场READY简历的明确姓名字段优先取名，正文缺姓名时参考“姓名-岗位.pdf”等文件名，明确提供给现有整理请求；解析生成稿及分析块缓存时再校正有原文自称依据的相近中文姓名，例如“吴佳彤”改为“吴佳童”。不泛化替换其他人姓名，原始转写、来源、其他回答内容和手工编辑保持；无资料、姓名字段冲突或差异过大时不自动替换。不增加独立AI调用或依赖。

缓存升级readable-qa-v6，旧v5/v4/v3/v2草稿继续读取和保存。更新后端运行版本后点击“重新识别问答”，复用已保存转写，无需重新上传录音。已留姓名资料优先级、来源约束、原文/手工编辑保留及缓存恢复的可运行检查；按用户要求未执行测试、构建、重启或真实请求，未提交、未推送。

### 2026-10-04：网络重试与问答格式修正分开计数

网络失败最多重试两次，字段/JSON/截断输出另有一次格式修正机会，仍受原分析总预算约束，主整理请求最多四次。网络错误不会覆盖已生成的字段修正提示；不可重试错误立即退出。kind/question/answer错误明确题号和字段，类型码兼容大小写，来源与字段校验保持；提示词明确标题不能为空，不丢弃实质问答来绕过校验。沿用v6，更新后端后重新识别现有转写即可。已留独立计数、反馈保留、重试上限和具体错误的可运行检查；未执行测试、构建、重启或真实请求，未提交、未推送。

### 2026-10-04：问答输入响应优化

问答卡片和发言/原文面板使用React.memo，编辑时只让当前卡片更新；原文对照和发言列表展开后才生成内容，展开后也不随其他问答的输入重复渲染。稳定操作回调读取最新草稿，输入即时更新，排序、排除、角色修改提示和确认提交保留原有流程。已留折叠来源不读取发言、编辑保留其他条目及排序/确认保留最新文字的可运行检查，未执行测试、构建或浏览器检查，未提交、未推送。

### 2026-10-04：面试复盘以整场表现与逐题改进为核心

代码已修改，待用户验收。复盘标题为“本场面试复盘”；完整场次总结目标600–1000个中文字符、4–6个自然段，短场次按实际内容缩短。总结仍最多4000字符，长度不足不重试。逐题依次展示回答表现、怎么改进、建议回答组织、次级判断依据、可以练习的追问；默认零到两个追问，空时隐藏。自我介绍、职业方向、候选人反问及面试官说明分别归属；只依据记录评价内容，不推断语速、紧张或招聘结论。

本场问答完整输入，JD、关联READY简历、证据卡仅用于理解背景，不作为标准答案。新报告不要求模型返回`missingEvidence`，旧模型字段忽略，服务端兼容列写空字符串；旧报告数据保留，前端统一隐藏该栏目。弱项输入与聊天复盘上下文不再消费该字段；弱项输入版本v3沿用原指纹/过期规则，旧口径快照需用户主动重新分析，GET不请求AI。

复盘预算仅影响`POST /api/v1/interviews/{id}/review`，不改变录音整理、弱项与通用聊天调用：

| 边界 | 当前实现 |
| --- | --- |
| 单次输入 | 完整Prompt与复盘JSON Schema合计最多32000个Java UTF-16字符（包括序列化转义），其中预留1000字符供格式修正；外围简历/JD/证据卡分别先限2500/1500/2000字符，再按剩余空间裁剪；问答不截断 |
| 单次输出 | 8192 tokens；每次最多6题是保守的输出预算代理，超过6题或完整输入放不下才分批；不是精确token计算或供应商容量保证 |
| 分批 | 原顺序按完整问题划分，局部Q1/Q2映射回真实ID；最多8批（至多48题，长回答可能更少），逐批完整评价后，以覆盖全场题目、回答关键信息及完整逐题结果另生成一次总结，再严格校验全量唯一覆盖 |
| 请求次数 | 默认合并请求1次；分批为批数+1次总结，整场最多10次（含格式修正）；不固定追加调用，无网络自动重试 |
| 格式修正 | 整场共享最多1次，仅结构校验/非法JSON；截断、供应商错误或额度耗尽明确失败，不保存部分结果 |
| 时间 | 从复盘入口起共900秒，单次超时取配置值（默认240秒）与整场剩余整秒的较小值；保存前后检查剩余预算，保存事务另限30秒 |
| 保存 | 全部生成和校验在事务外；TransactionTemplate短事务锁定归属与问答，复核ID、顺序、内容、自评、反馈及面试信息；问答写入共享面试锁，变化则拒绝旧结果，事务内故障全部回滚 |

2026-10-04用户验收排查：20:55第二批请求返回HTTP 200后出现`JsonParseException`，整场唯一一次格式修正后出现`JsonEOFException`；与此前Cockpit中转断流返回408属于不同阶段。现有日志没有模型全文，不能还原具体坏字符，也不能把EOF直接认定为8192-token额度截断。复盘契约已改为序列化的合法JSON示例，明确双引号/反斜杠/段落换行转义及完整闭合；修正提示带安全错误类型/位置。复盘专用解析保留完整Markdown代码围栏兼容，不再截取首尾大括号掩盖额外或未完成内容，严格拒绝多份JSON、前后解释及未闭合输出；录音整理、弱项与通用聊天原解析行为保持。诊断仅记录完成原因白名单、内容长度、异常类型及行/列/偏移，不记录模型全文或解析器原始报错（可能含回答内容）。预算和次数未增加，修正后仍失败不保存；已补充合法换行/引号、EOF、前后文本、多对象、原调用兼容及修正失败保留旧报告的回归用例，未执行。代码已修改，待用户验收，尚不能保证上游每次生成有效JSON。

单题完整问答（含JSON转义）超限、超过8批、整场总结输入超限、任一批失败/截断/校验失败或预算耗尽，均明确失败，原问答和旧报告保留。字符与题数阈值尚未经真实模型样本验证，模型的段落质量、事实归属及具体建议仍需用户验收，不能保证供应商不截断。

更新运行版本后，用户主动点击“生成复盘 / 重新复盘”才新增新口径报告；刷新只读已有报告，不改写旧总结，不重新转写或整理录音。沿用现有API、DTO和数据库，无迁移、依赖、Agent或队列。首次实施阶段只补充Java接口/预算/下游与页面回归用例，未执行检查；后续用户授权自测的结果见下方。改动未提交。相关检查可重新运行（命令需在对应目录执行）：

```powershell
# server目录；包含本次回归用例
mvn -o -B -ntp -s .mvn/settings.xml "-Dtest=InterviewControllerTest,ReviewModelClientTest,WeaknessControllerTest,AiConversationControllerTest" "-Dapp.agent.url=http://127.0.0.1:9" test
# web目录；页面检查，不代表真实浏览器/模型效果
node --test app/interviews/page.test.cjs
```

### 2026-10-04：复盘结构化输出修复与授权自测

21:21的首批响应以`finish_reason=stop`返回未闭合JSON，一次修正成功后，21:23第三批又在3209字符内容的第3207列出现语法错误。仅增加提示未解决输出稳定性；原始响应未保存，不能断言该字符具体是尾逗号或何种内容。

复盘专用请求改为`response_format.type=json_schema`、`strict=true`，根据合并报告/逐题批次/整场总结发送对应Schema：全部字段必填、对象不允许新增属性、readiness与弱项标签使用固定枚举、questionId只允许本批Q编号，不含missingEvidence。规则依据[OpenAI Docs结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)。服务端继续独立校验类型、长度、标签和全量唯一ID，Schema纳入32000字符预算；不增加调用/修正/时间额度，不截断原回答，也不自动补括号或丢题。中转若以HTTP400/422拒绝结构化请求，会明确提示检查中转支持与模型配置，不自动降级或额外请求。录音整理、弱项及通用聊天沿用原调用。

按用户新的自测授权，最终本地检查通过：后端四组35项回归（H2内存库、模型Mock或127.0.0.1模拟HTTP）、前端14项页面回归、TypeScript检查、后端Maven打包及Next生产构建。模拟HTTP覆盖`stop`但JSON未闭合、首次修正成功后后续批次再次出现尾部语法错误、修正额度耗尽不保存半份报告、HTTP400拒绝时不重试；覆盖三种Schema与完整输入预算。前端生产构建在`server/target/review-web-build-20261004`隔离目录，使用占位公共配置，未覆盖正在运行的生产/开发目录，也未重启现有服务。前端旧来源用例改为展开后核对，保留原文按需渲染。

首次基线测试暴露已有后台任务会调用本机Agent；已向用户说明这一意外，并在本次接口测试中Mock后台worker/Agent、断言不调用外部Agent，最终回归日志没有真实Agent调用。环境文件、真实数据库、原问答与历史报告未改动。`InterviewReviewGatewaySmokeTest`是另行明确授权后才能运行的真实中转检查，默认跳过，只调用一次模型、使用合成六题、不启动应用上下文、不连接数据库或保存报告。代码已修改，待用户验收；本地检查不等于所有真实长场次的模型质量已经通过。

用户随后明确授权一次合成六题请求。21:45当前Cockpit中转接受严格Schema请求并返回HTTP200，用时约51秒，JSON解析、固定标签/长度与六题唯一全量覆盖校验通过，总结687字符，调用次数1、无数据库访问。日志在`server/target/review-gateway-smoke.log`；这只证明该合成样本通过，不保证中转永不断流、始终执行Schema约束或所有真实长场次的内容质量。默认本地回归不会运行此真实检查；若将来要重跑，须先另行授权真实请求，再在server目录运行`mvn -o -B -ntp -s .mvn/settings.xml "-Dtest=InterviewReviewGatewaySmokeTest" "-Dreview.gateway.smoke=true" test`。当前运行服务没有重启，需用户更新后端运行版本后主动重新生成报告才使用本次契约。
