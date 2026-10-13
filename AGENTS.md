# 项目长期开发规则

## 核心依据与范围

- 开始任务前阅读根目录《医疗 RAG 系统｜完整需求规格说明 v1.0.md》以及 `docs/分阶段开发计划-v1.0.md` 的相关章节。两份文件是长期需求依据，不删除、不覆盖重要细节。
- 用户已确认 A1～A5 Windows 验收符合要求；A4 日志占用修复及完整 53 项 Windows 测试已确认，A5 Windows 验收由用户确认。本次 2026-10-14（Asia/Shanghai）授权仅为 A6 PDF 上传、列表、预览与删除，基于 A5 提交 4a93e0ee5602ca22d3df416e68522184c4f2837a，在 codex/a6-pdf-document-management 提交并推送 origin。完成后停止，等待 A6 Windows 验收及下一任务授权；不自动合并 main 或发布 Release。
- 用户现有 Windows 数据库管理员为 mianmian，禁止清空该数据库、改写实际账号或删除其数据卷。首次初始化测试仅使用隔离测试数据库；直接复用 A2 的认证接口和架构。
- 严格遵循 P0/P1/P2 与 A→B→C→D→E。不得为了提前展示功能而开发后续阶段、编写假业务接口、假数据或假检索日志。
- 新技术栈、收费服务或重大功能删改先说明影响并征求用户意见。使用 Vue 3/Vite/Element Plus、Java 21/Spring Boot、PostgreSQL/pgvector、Flyway；后续按计划使用 Spring Security、Spring AI、PDFBox、PDF.js、Vue Konva。
- 保持单体应用和本地优先，不随意加入微服务、Redis、消息队列、复杂 Agent 框架或模型训练。

## 不可削减的系统要求

- 后端登录认证及数据归属检查；多知识库隔离，不能只由前端过滤。
- 可编辑和可追溯 Chunk、疾病三级图库、人工标注、全站每张图片独立原图/标注图切换。临时图层和视图位置不能因切换丢失。
- 原 PDF 和原图不可被编辑覆盖，人工更正、批注和图层单独保存。
- 医学答案仅依据当前库证据，证据不足拒答；历史对话不是医学事实来源。不联网补答案，不自动识图或诊断。
- API Key 仅由后端使用，加密保存，主密钥与数据库分离，不回传原密钥、不写日志。

## 实现与验证

- 面向 Windows 初学者提供中文安装、配置、启动、验证和故障排查说明。开发服务仅绑定回环地址。
- 每个任务实际编写运行代码，执行必要测试及受影响功能回归；数据库集成测试使用真实 PostgreSQL/pgvector，不用 H2 冒充。
- 云环境、浏览器自动化、真实 Windows 测试分别记录；没执行或跳过的测试不能报成功。A6 只管理 PDF 原件、实际页数、预览下载删除、真实计数和非空库清理；不开发 A7 图片或 B 的全文提取/Chunk/索引/RAG，不提供假重处理按钮。
- 知识库 owner_id 只从验证后的当前用户获取；详情、写、删 SQL 都限定 owner_id。不存在与非所属资源使用相同安全错误；请求不能改 ID、owner_id 或演示属性。
- A4 的知识库 ID 在 JSON 中用十进制字符串保持 BIGINT 精度；当前库由路由 ID 与实时后端详情建立，退出/过期和删除后清理，不能只相信浏览器缓存。
- A6 documents 已有真实统计：仅 ACTIVE 计入 documentCount，其他生命周期记录单独计入 pendingCleanupCount，非空库删除展示真实影响并清理原件。medical_images 尚不存在，imageCount 实际为 0；发现不匹配图片表必须明确拒绝。A7 接入真实图片计数与 KnowledgeBaseMaterialCleaner，资料外键保持 RESTRICT，不能用 CASCADE 绕过文件清理。
- A5 原件根目录必须私有、持久化；客户端路径/原名称不作为磁盘定位依据。保留服务器 UUID 命名、流式大小/SHA-256、同文件系统临时目录原子提交、失败补偿及逐层路径/符号链接检查；不自动扫删未知文件，不覆盖原件，不静默降级半文件提交。
- A6/A7 只能经 KnowledgeBaseFileStorage 调用存储。先用资源 ID 查询数据库记录，校验当前登录账号与所属知识库，再使用数据库中的 StoredOriginal；不能直接绑定 HTTP 中的路径或凭据。文件清理失败须明确记录并可重试；删除知识库时保留归属信息直到关联清理完成。A5 没有跨数据库/文件的自动事务。
- 上传前持久化服务器 StorageReservation；未完成记录的受控 discardReservation 只处理该意图的精确对象，不扫描未知内容。UPLOADING/ACTIVE/DELETING/DELETE_FAILED 与 PENDING/PROCESSING/INDEXED/FAILED 分离；A6 成功上传只 PENDING。读写与删除使用同一知识库锁并重查删除标志，保留失败凭据供重试；活动原件仍校验大小和 SHA-256，不能直接拿任意磁盘路径给 PDFBox。
- PDFBox 在 A6 只做严格结构与页数，PDF.js 真正渲染页面；退出/切文献释放 worker/渲染任务。当前提供完整文件 HTTP 200、Accept-Ranges: none，不声称支持 Range；文献页码深链仅允许受控 ID 和 page 参数。后续来源跳转复用预览组件。
- 存储单元/集成/浏览器及重启子 JVM 都必须使用新建专用临时根目录和测试大小设置，不继承实际 APP_STORAGE_ROOT，不清理实际 data/。符号链接权限或 POSIX 不支持时记录具体受限原因，不算已通过。
- 依赖锁定版本，升级先核对兼容范围；迁移用 Flyway，不修改已发布迁移、不靠自动建表替代版本管理。
- `.env`、数据库密码、密钥、私人原件、运行数据、日志、备份和截图测试产物不提交。模板只含非敏感样例。
- 保留正常 Git 提交；在当前仓库按任务分支工作，不创建新仓库、不改写已有历史，不自动发布正式 Release。

## A1 常用命令

- 前端：`cd frontend`，`npm ci`，`npm run build`，`npm run test`。
- 后端：`cd backend`，Windows 执行 `.\mvnw.cmd verify`；Linux 执行 `./mvnw verify`。
- 真数据库集成测试：Maven 增加 `-Pintegration-test`，需要可访问的 Docker；普通 verify 不执行该组测试。
- 启动：参考 README 中 `scripts/Initialize-Database.ps1`、`Start-Backend.ps1`、`Start-Frontend.ps1`。
- 浏览器测试：先构建后端 jar，再在 frontend 执行 `npm run test:e2e`。启动器分配独立本机数据库、后端及前端端口，测试结束仅清理本次临时容器；不要直接针对已有数据库执行首次 setup 测试。
- A2 API/Windows 操作参考 `docs/A2-管理员认证操作.md`；初始化、登录、退出都必须携带当前 Session 的 CSRF 令牌，登录后重新获取令牌。
- A3 运行与验收参考 `docs/A3-前端运行与验收.md`。不要保存密码、CSRF 或登录信息到 localStorage/sessionStorage，不记录认证网络 trace。
- A4 增量迁移、运行与验收参考 `docs/A4-知识库运行与验收.md`。跨用户夹具只在事务回滚的独立测试数据库中暂时放宽单管理员约束；严禁对实际数据库这样操作。浏览器启动器的重启控制只操纵其自建子进程，不属于产品 API。
- A5 默认根目录 data/originals（相对仓库根），PDF 50 MiB、图片 20 MiB；旧 .env 无需补配置即可运行。V1/V2/V3 不改动；A6 只增量 V4 文献表及库删除标志，图片完整解码及像素限制留 A7。
- A6 获取代码、依赖安装、测试及一次性网页验收见 `docs/A6-PDF文献管理运行与验收.md`，结果见 `docs/A6-验证记录.md`。更新代码后执行 npm ci，PDF.js 需要 Node 24 或 22.13+；不要因为已有旧 node_modules 而漏装新依赖。
