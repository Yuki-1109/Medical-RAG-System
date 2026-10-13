# Medical-RAG-System

医疗文献与医学图片知识库 RAG 系统，本科毕业设计，面向 Windows 本地部署。

**当前交付：A7 图片管理 + A8 阶段 A 综合验收。** 登录、多知识库、PDF原文页码预览、JPG/JPEG/PNG原图管理、人工类型/分期/程度/关键词/备注、原件下载校验、确认删除和可重试联合清理均为真实功能。A7增量V5保留原A1～A6账号、资料和迁移；人工标签不改原件、不做医学识图。

A1～A6 Windows核心验收已由用户确认，A6 PDF原件与下载SHA-256一致已确认；A7/A8 Windows综合验收待用户实机执行。一次性命令及完整网页操作见 **[A8 Windows综合验收](docs/A8-阶段A综合验收.md)**；[A7实现及运行](docs/A7-医学图片管理运行与验收.md)、[A7测试](docs/A7-验证记录.md)、[阶段A最终记录](docs/A阶段-最终验收记录.md)。保留原mianmian、.env、卷、PDF和原件目录，不需重新初始化。

阶段A完成不等于整个RAG MVP完成：模型配置、文字提取/Chunk/Embedding/检索/问答、三级疾病分类、图层和双视图、历史会话、Compose/Release尚未实现。当前不需要模型API Key，等待Windows验收后再由用户明确授权B1。Linux最终回归：后端205（72单元/133集成）、前端41单元及29浏览器通过；Windows新增阶段待验收，完整生产专项结果见阶段A记录。

需求依据：[完整需求规格说明 v1.0](医疗%20RAG%20系统｜完整需求规格说明%20v1.0.md)、[分阶段开发计划](docs/分阶段开发计划-v1.0.md)。后续按小任务开发，每次验收后等待用户确认。

## 1. 安装软件（Windows）

1. **Git for Windows**：用于下载及同步代码，安装后打开新的 PowerShell。
2. **Java JDK 21**：例如 Eclipse Temurin 21。安装时启用 `JAVA_HOME` 与 PATH 配置；不是只安装 JRE。
3. **Node.js 24 LTS**：附带 npm；也支持 Node 22.13 及以上的 22 LTS（PDF.js 的要求）。不要使用过旧 Node。
4. **Docker Desktop**：按官方要求启用 WSL 2/虚拟化，打开 Docker Desktop 等待就绪，使用 Linux containers。这里只用 Docker 运行数据库，前后端仍在 Windows 本机启动；整套 Compose 在 E 阶段实现。

软件的官方安装说明：[Git](https://git-scm.com/downloads/win)、[JDK](https://adoptium.net/temurin/releases/?version=21)、[Node.js](https://nodejs.org/)、[Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/)。首次下载依赖需要网络。

在 PowerShell 检查：

```powershell
git --version
java -version
node --version
npm.cmd --version
docker version
```

Java 应为 21，Node 推荐 24；Docker 输出须包含 Client 和 Server。不需要额外安装 Maven、PostgreSQL 或 pgAdmin：Maven Wrapper 下载固定 Maven，数据库镜像已包含 pgvector。

## 2. 下载代码与准备数据库

已存在仓库时保留原 `.env` 和数据库，不重新创建另一个仓库；先同步当前任务分支。首次下载示例：

```powershell
git clone https://github.com/Yuki-1109/Medical-RAG-System.git
cd Medical-RAG-System
# 合并 main 前使用已推送的 A7/A8 分支：
git switch codex/a7-a8-phase-a-acceptance
```

已有数据库升级 A7/A8 只需启动原容器 `docker start medical-rag-postgres`，启动后端增量执行 V5（仍为V3的数据库会先执行V4），保留原 .env；在 frontend 执行 `npm.cmd ci` 安装新增预览依赖。详细 fetch/switch 命令见 A8 文档。以下初始化命令用于第一次安装新环境；已有管理员不重新创建。

在仓库根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Initialize-Database.ps1
```

此命令仅对本次脚本进程使用 ExecutionPolicy Bypass，不修改系统策略。脚本会：

- 复制 `.env.example` 为本机 `.env`，生成随机数据库密码，不把密码输出到终端。
- 下载固定 `pgvector/pgvector:0.8.2-pg17` 镜像，启动 `medical-rag-postgres` 容器。
- 使用命名数据卷 `medical-rag-pgdata` 保存数据库，监听 `127.0.0.1:5433`，避免与常见 5432 冲突。
- 已有容器时启动现有容器，保留数据。不要删除数据卷；修改 `.env` 密码不会修改已有数据库密码。

看到“PostgreSQL 已就绪”后继续。Flyway V1 启用 pgvector，V2 创建 users 及单管理员约束（不预设账号或密码），V3 创建知识库。A6 V4新增documents与库删除标志，A7 V5新增medical_images、人工基础信息/生命周期/索引；保留原账号、密码哈希和知识库，不删除数据卷、不修改 V1～V4、不重新初始化数据库。资料外键 RESTRICT，不使用 CASCADE 绕过原件清理。

`.env` 只留在本机，不截图公开、不提交 Git。配置示例中的 DB_PASSWORD 为空是有意设计，应用没有默认密码。初始化脚本适合 A1 开发，数据库角色拥有创建扩展所需权限；正式发布时再收紧迁移/运行账号权限。

### 已有本地 PostgreSQL 的可选方式

推荐初学者使用上述镜像。若已有 PostgreSQL，须先安装与 PostgreSQL 版本匹配的 **pgvector 扩展文件**；普通 PostgreSQL 安装包不一定包含它。然后在 pgAdmin/psql 中创建独立角色及数据库，确认当前数据库已执行 `CREATE EXTENSION vector;`，复制 `.env.example` 为 `.env`，修改主机、端口、库名、用户名和密码。不得把真实密码写入 SQL 示例或 Git。

Flyway 对已安装的扩展执行 `CREATE EXTENSION IF NOT EXISTS vector`。缺少扩展安装文件或权限时后端启动会明确失败，不会跳过迁移冒充成功。

## 3. 启动后端

打开第一个 PowerShell，进入仓库根目录：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
```

第一次会下载 Maven 3.9.11 与依赖，耗时取决于网络。成功时看到 `Started MedicalRagApplication` 与端口 8080；Flyway 按顺序执行尚未完成的 V1～V5，已有 V4 只增量执行 V5（V3 会先执行 V4），再次启动不重复迁移。只有 A2 setup API 会创建管理员，健康接口仍公开可用。

直接访问 [后端健康接口](http://127.0.0.1:8080/api/health) 可查看 JSON。后端正常时状态为 `UP`；响应不包含密码、数据库连接字符串或用户名。8080 当前只提供 API，页面在前端 5173。

A5 默认原件目录为仓库的 `data/originals`，PDF 上限 50 MiB、单图 20 MiB；**原 .env 不需要修改**。如需自定义，仅追加原 .env 中缺少的 APP_STORAGE_ROOT、APP_STORAGE_MAX_PDF_BYTES、APP_STORAGE_MAX_IMAGE_BYTES，参考 A5 运行文档，不用模板覆盖旧文件。组件启动只创建受控目录，不删除原件。路径不可用或配置无效会明确失败，不退回临时目录；原件不会成为公开静态文件。

## 4. 启动前端并打开网站

保持后端运行，打开第二个 PowerShell，进入同一个仓库根目录：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Frontend.ps1
```

首次会自动执行 `npm ci` 安装锁定依赖；从旧分支更新后即使已有 node_modules，也先在 frontend 手动执行 `npm.cmd ci`。看到 Vite 的 Local 地址后，浏览器打开：

**[http://127.0.0.1:5173](http://127.0.0.1:5173)**

已有管理员时进入登录页，输入本机已有账号及密码；你的 mianmian 账号保持原样。登录后进入中文首页，显示真实账号、创建时间、知识库管理和服务检查入口。未初始化的全新安装才显示创建管理员页，创建后仍需登录。

未登录直接输入 `/app` 或 `/app/health` 会转到登录页；刷新会重新询问后端当前账号。登录成功重新获取 CSRF，退出及会话失效会清理前端登录状态；不把密码、Cookie 或 CSRF 存到浏览器本地存储。

A1 的启动检查页保留在 [http://127.0.0.1:5173/health](http://127.0.0.1:5173/health)，登录后也可通过左侧“服务检查”进入。后端、PostgreSQL、pgvector、Flyway 四项应显示“正常”。知识库支持卡片列表、新建、编辑、切换及确认删除，名称 1～100 字符、简介最多 2000 字符，ID 为字符串。进入当前库的“文献管理”可上传、预览、下载和确认删除 PDF；文献计数真实查询数据库，非空库删除展示影响并清理原件，失败保留凭据供重试。医学图片可上传JPG/PNG、查看原图、编辑人工信息、下载和确认删除；片段、疾病分类/图层、问答等仍待开发。

通信流程：浏览器 `/api/health` → Vite 本机代理 → Spring Boot → 实际 PostgreSQL 查询。数据库检查失败返回 HTTP 503；前端会展示真实组件状态。后端未启动时提示连接失败。

后续 E 阶段将打包为后端统一托管页面、使用 8080；本次无需提前配置完整网站发布。

## 5. 停止、重启与简单故障验证

前后端分别在所在窗口按 `Ctrl+C`。停止数据库：

```powershell
docker stop medical-rag-postgres
```

停止数据库后在 `/health` 页点击“重新检查”，应显示后端仍响应、数据库异常、扩展和迁移无法检查；不能仍显示全部正常。再次运行初始化脚本启动原数据库后，重新检查应恢复正常。不要删除数据卷或清空 users 表。

如果启动时数据库不可用，Flyway 初始化会失败，后端不会成功启动。先恢复数据库，再启动后端。

## 6. 常见问题

| 问题 | 检查与处理 |
|---|---|
| 找不到 java/node/docker | 安装对应软件后重开 PowerShell；确认 PATH 和 Java 21 |
| release version 21 not supported / 无编译器 | 确认安装完整 JDK 21；执行 `javac -version` 和 `backend\mvnw.cmd -version`，将 JAVA_HOME 指向 JDK 21 根目录而非 bin，重开终端 |
| Docker 没有 Server 或无法连接 | 打开 Docker Desktop，等待就绪，确认 WSL 2 与 Linux containers |
| 下载镜像/依赖失败 | 检查网络、公司代理和终端错误；保留 TLS 验证，不关闭证书检查 |
| 5433 被占用 | 第一次初始化前更改 `.env` 的 DB_PORT；已有容器不能只改配置端口，要先核对容器映射并保留数据卷 |
| 8080/5173 被占用 | 修改 `.env` 的 SERVER_PORT/FRONTEND_PORT，重启两个服务；浏览器使用对应前端端口 |
| 数据库鉴权失败 | 核对 `.env` 与第一次初始化时的数据库账号密码；已有数据卷不会随环境变量自动改密码 |
| vector extension 不可用 | 使用规定的 pgvector 镜像；自行安装 PostgreSQL 时需安装匹配扩展文件和权限 |
| Flyway 校验失败 | 不改动已经执行的迁移；核对版本，保存数据后排查，不禁用 Flyway 绕过 |
| 页面连接失败 | 先访问 8080 健康接口；确认后端运行、SERVER_PORT 一致，再重启前端 |
| PowerShell 不允许运行脚本 | 使用本文逐次进程 Bypass 命令，不需要管理员修改全局执行策略 |

## 7. 开发与测试命令

在仓库根目录打开 PowerShell：

```powershell
cd frontend
npm.cmd ci
npm.cmd run test
npm.cmd run build
cd ..\backend
.\mvnw.cmd verify
# 需要 Docker：创建一次性真实 PostgreSQL/pgvector 测试容器，不使用用户数据库
.\mvnw.cmd verify -Pintegration-test
```

普通后端 verify 执行单元测试并打包；**不代表数据库集成测试已执行**。带 `-Pintegration-test` 才会运行真实数据库健康、迁移幂等、扩展缺失、迁移记录缺失、A2 认证、A4 CRUD/归属/CSRF、V2→V3 升级和知识库重启持久化测试；其中包含 8 路并发初始化、真实 HTTP Session/CSRF、退出失效和 jar 进程重启后登录。A5 增加真实文件读写/哈希/补偿/路径与权限测试，以及实际 Spring Boot 子 JVM 重启后的原件读取。测试使用独立临时数据库与原件根目录，不创建/删除用户本机管理员，不使用实际 APP_STORAGE_ROOT。没有 Docker 时该命令应失败，不把跳过当成功。

A6 Linux 实际结果：后端 62 项单元 + 82 项集成，共 144 项通过（0 失败/错误/跳过），完整保留 A1～A5 的 100 项；前端 35 项单元、类型/生产构建、23 项浏览器回归及 1 项生产构建完整流程通过。A6 Windows核心验收已由用户确认，A7/A8 Windows综合验收待实机验证；权限受限符号链接及不支持的 POSIX 场景须明确报告原因，不能计为通过。

浏览器测试使用自动隔离的真实数据库与后端，不使用用户数据库。先执行上述 Maven verify 生成 jar，并确保本机 Docker 已就绪：

```powershell
cd frontend
npx.cmd playwright install chromium
npm.cmd run test:e2e
```

测试启动器分配独立 PostgreSQL 容器、后端及 Vite 端口；只在该数据库首次创建测试管理员，结束后只清理本次临时容器及匿名卷。现有 medical-rag-postgres / medical-rag-pgdata 保持不变，不要绕过启动器直接执行认证用例。

覆盖真实创建→登录→刷新→退出→再次登录、已有账号、私有路由、错误密码、服务断开、失效、HttpOnly，以及 K1/K2 创建/编辑/切换/刷新/重启、删除取消与确认、失效当前库和笔记本布局。重启只操纵测试启动器自建子进程，产品后端没有重启接口。独立测试后端临时将闲置超时设为 1 分钟，实际等待 65 秒验证过期；生产仍为 A2 的 30 分钟。认证网络 trace 禁用，日志与测试截图不提交。测试数据仅属于隔离用例，不用于生产界面。

Linux 可在 backend 执行 `./mvnw verify` / `./mvnw spring-boot:run`，前端使用 npm 相同命令；后端与 Vite 都读取仓库根目录 `.env`。PowerShell 启动脚本面向 Windows，不代表已在 Windows 实机执行。

## 8. 版本、许可证与 Git 同步

- Vue 3.5.44、Vite 8.3.4、Element Plus 2.14.7、Vue Router 4.6.4；Java 21、Spring Boot 3.5.16、Maven 3.9.11。
- A6 增加 PDFBox 3.0.8 与 PDF.js 6.4.299，核心均 Apache-2.0；字体/WASM 子资源保留独立许可证，具体版本、许可与本地资源构建见 A6 验证记录。
- Spring AI 官方说明 `1.1.x` 对应 Boot `3.5.x`；A1 不加载模型依赖，不需要 Key。后续 B 再选择同系列稳定补丁。
- Maven 的数据库/Flyway/测试库版本由 Spring Boot BOM 统一管理，前端精确版本及 package-lock 固定。依赖及许可证说明见 [A1 依赖说明](docs/A1-依赖说明.md)。项目开源许可证将在公开发布前确认，本次不擅自替用户选择代码授权。

代码保存在独立 `codex/a7-a8-phase-a-acceptance` 分支，基于已验收A6 `7567c0391b8d6844364f9f0759896ce3a82d1d0c`；正常历史提交保留。本次已授权实现A7/A8、测试、提交及推送，仅推开发分支，不合并main、不发布Release。Windows显式fetch/switch/ff-only见[A8一次性验收](docs/A8-阶段A综合验收.md)。

不要提交.env、个人原件、数据库、日志或备份。完成后停止，Windows综合验收确认且另行明确授权后才开始B1模型安全配置。
