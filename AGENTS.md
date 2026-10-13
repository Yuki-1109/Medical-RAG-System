# 项目长期开发规则

## 核心依据与范围

- 开始任务前阅读根目录《医疗 RAG 系统｜完整需求规格说明 v1.0.md》以及 `docs/分阶段开发计划-v1.0.md` 的相关章节。两份文件是长期需求依据，不删除、不覆盖重要细节。
- 用户已确认 A1/A2/A3 的 Windows 核心验收；2026-10-13 当前授权仅为 A4 知识库 CRUD 与后端隔离，包含独立分支提交及推送 origin。完成后停止，等待 Windows 验收和下一任务授权；不自动合并 main 或发布 Release。
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
- 云环境、浏览器自动化、真实 Windows 测试分别记录；没执行或跳过的测试不能报成功。A4 只增加知识库管理，不开发 A5 存储、A6/A7 上传或后续 RAG，不能描述为完整系统。
- 知识库 owner_id 只从验证后的当前用户获取；详情、写、删 SQL 都限定 owner_id。不存在与非所属资源使用相同安全错误；请求不能改 ID、owner_id 或演示属性。
- A4 的知识库 ID 在 JSON 中用十进制字符串保持 BIGINT 精度；当前库由路由 ID 与实时后端详情建立，退出/过期和删除后清理，不能只相信浏览器缓存。
- 当前没有资料业务表，A4 空库计数有明确阶段校验；发现 documents/medical_images 时拒绝继续伪报 0 或空库删除。A6/A7 必须替换 KnowledgeBaseMaterials/Repository 的计数为实际归属资料统计，并加入非空库删除说明与清理，未来资源外键使用 RESTRICT，不绕过关联保护。
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
