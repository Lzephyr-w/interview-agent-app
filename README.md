# AI 简历助手

面向求职准备的全栈 AI 面试辅助应用。用户可以管理简历、JD 和项目证据卡，建立面试包，完成文本模拟或限时录音模拟，并将真实面试记录整理为可追溯的复盘、薄弱点和训练任务。

## 功能

- 简历文件上传与 PDF、DOC、DOCX 文本解析
- JD、项目证据卡和面试包管理
- 真实面试记录、问题整理与 AI 结构化复盘
- 独立文本模拟：AI 出题、追问与逐题反馈
- AI 录音模拟：10 道题、每题 5 分钟，录音后自动转写和复盘
- 薄弱点聚合、训练任务和首页冲刺清单
- 基于当前用户授权资料的 AI 对话
- JWT 鉴权、用户数据隔离和私有音频存储

## 技术栈

| 模块 | 技术 |
| --- | --- |
| Web | Next.js 15、React 19、TypeScript |
| Server | Java 21、Spring Boot 3.4、Spring Security、JDBC |
| 数据 | PostgreSQL / Supabase、Flyway |
| 存储与认证 | Supabase Auth、私有 Storage bucket |
| AI | OpenAI 兼容 Chat Completions API；OpenAI 兼容或火山引擎音频转写 |
| 测试 | JUnit 5、Spring Boot Test、H2 |

## 项目结构

```text
interview-agent-app/
├─ server/                         # Spring Boot API、Flyway 迁移与后端测试
│  └─ src/main/resources/db/migration/
├─ web/                            # Next.js 前端
├─ infra/                          # 基础设施配置预留目录
└─ README.md
```

## 本地运行

### 1. 环境要求

- Node.js 20+
- pnpm
- Java 21+
- Maven 3.9+
- Supabase 项目（Auth、PostgreSQL、Storage）

### 2. 配置 Supabase

创建以下私有 bucket，并确保未开启 Public：

- `resume-files`：简历原文件
- `ai-mock-audio`：模拟面试录音

数据库默认使用 `interview_agent` schema。后端启动时会通过 Flyway 自动执行 `V1` 至 `V16` 迁移，请勿手工修改已执行的迁移文件。

### 3. 配置环境变量

复制示例文件：

```powershell
Copy-Item web/.env.example web/.env.local
Copy-Item server/.env.example server/.env.local
```

前端只允许配置公开值：

- `NEXT_PUBLIC_API_BASE_URL`
- `NEXT_PUBLIC_SUPABASE_URL`
- `NEXT_PUBLIC_SUPABASE_ANON_KEY`

数据库密码、Service Role Key、JWT Secret、模型和转写密钥只能配置在服务端。完整变量及用途见 [`server/.env.example`](server/.env.example) 和 [`web/.env.example`](web/.env.example)。

### 4. 启动后端

```powershell
cd server
mvn -B -ntp -s .mvn/settings.xml spring-boot:run
```

后端默认运行于 `http://localhost:8080`，健康检查：

```text
GET http://localhost:8080/actuator/health
```

### 5. 启动前端

```powershell
cd web
pnpm install
pnpm dev
```

前端默认运行于 `http://localhost:3000`。

## 质量检查

```powershell
# 后端完整测试
cd server
mvn -B -ntp -s .mvn/settings.xml test

# 前端类型检查与生产构建
cd ../web
pnpm run lint
pnpm run build
```

## 安全与隐私

- 所有用户数据接口均从已验签 JWT 的 `sub` 获取身份，不接受前端指定 `userId`。
- 跨用户访问返回 404 等非泄露响应。
- 简历和录音通过服务端访问私有 bucket；Service Role Key 不进入浏览器。
- 录音仅用于本次转写与面试复盘，删除录音或会话时同步清理关联存储对象。
- AI 输入和结构化输出在写库前执行空值、长度及 JSON 结构校验。
- 不要提交 `.env.local`、日志、真实录音地址、访问令牌或任何生产密钥。

## 当前边界

- AI 录音模拟固定 10 道题，每题限时 5 分钟。
- 文本模拟是独立页面，不复用录音、转写或倒计时流程。
- 系统用于用户主动求职训练，不提供真实面试中的隐蔽实时提词。
- AI 结果是训练建议，不代表能力评级、录用概率或招聘结论。
