# B1 模型 API 安全配置与 Windows 验收

本次仅 B1 与知识库两个入口按钮统一，等待 Windows 实机验收；没有 B2 文本解析、Chunk、批量向量化、索引或聊天页面。A7/A8 已由用户确认 Windows 验收，历史 A8 偶发 `#pdf-file` 超时及 Windows 存储测试跳过记录仍保留，不改写成从未失败。

## 1. 准确基线及安全更新

开发基线：`c5fb58da2ac50c4ae652f45e8ad2d8457f80a6eb`，树 `cd17e166b103b259d4de617c57034b3caf359586`。开发前本地 work 实为 `cf64bf69fdcf2971da463dcbc84aa41341a58a7d`，不能作为 A7/A8 基线。已安全 fetch 指定提交并 switch 创建 `codex/b1-model-configuration`，没有 reset、强推、关闭 TLS、覆盖 main 或 A6/A7/A8。最终完整 SHA 见交付报告，并用下面命令与 GitHub 实际分支核对；本文件不自引用包含自身的提交 SHA。

停止正在运行的前后端（分别 Ctrl+C），先正常备份原数据库、原件和 `.env`。不要运行 Initialize-Database、Flyway clean、重置账号、删除容器或数据卷。Docker Desktop 开启，等待 Server 就绪，使用 Linux containers。

PowerShell 整段复制；若路径不同，只改第一行：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
if (git status --porcelain --untracked-files=normal) { throw '存在未提交文件，请先保存；不要强制重置。' }
git fetch origin refs/heads/codex/b1-model-configuration:refs/remotes/origin/codex/b1-model-configuration
if ($LASTEXITCODE -ne 0) { throw '获取 B1 分支失败，停止更新。' }
git show-ref --verify --quiet refs/heads/codex/b1-model-configuration
if ($LASTEXITCODE -eq 0) { git switch codex/b1-model-configuration } else { git switch --track origin/codex/b1-model-configuration }
if ($LASTEXITCODE -ne 0) { throw '切换失败，停止。' }
git merge --ff-only origin/codex/b1-model-configuration
if ($LASTEXITCODE -ne 0) { throw '分支存在分歧，请保留现有提交，不强推或重置。' }
git branch --show-current
git log -1 --format='%H %s'
git ls-remote --heads origin refs/heads/codex/b1-model-configuration
git merge-base --is-ancestor c5fb58da2ac50c4ae652f45e8ad2d8457f80a6eb HEAD
if ($LASTEXITCODE -ne 0) { throw '当前提交不包含验收基线。' }
git status --short --branch
```

当前分支必须是 B1；本地 HEAD、远端分支 SHA 和本次交付报告 SHA 一致。现有 `.env` 不动，旧环境采用安全默认值。`.env.example` 仅新增两个可选项，**不用模板覆盖原 `.env`**：

```properties
APP_MODEL_KEY_FILE=secrets/model-master.key
APP_MODEL_ALLOW_LOOPBACK_HTTP=false
```

默认远程只允许公共 HTTPS API。确有受信任的本机兼容服务时才在自己的 `.env` 增加 `APP_MODEL_ALLOW_LOOPBACK_HTTP=true`，重启后允许字面地址 `http://127.0.0.1:端口/v1` 或 `http://[::1]:端口/v1`。不允许 localhost 域名、局域网、云元数据或其他私网地址。不要把该开关用于未知的本机服务。

## 2. 启动已有环境与增量 V6

先安装锁定前端依赖：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System\frontend'
npm.cmd ci
if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
Set-Location '..'
java -version
javac -version
node --version
docker version
```

Java 与 javac 均应是完整 JDK21；Node24 或22.13+。第一个 PowerShell 窗口：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
docker start medical-rag-postgres
if ($LASTEXITCODE -ne 0) { throw '原数据库未能启动，勿重建数据卷。' }
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
```

第二个窗口：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Frontend.ps1
```

浏览器打开 `http://127.0.0.1:5173`，用 mianmian 原密码登录；若旧 `.env` 改了端口，以终端地址为准。**不应再次要求创建管理员**。服务检查显示 Flyway 版本 6。首次启动仅新增 V6（已在 V5 的库），不修改 V1～V5 SQL/checksum，重启不重复迁移。

V6 创建 `model_configs`：owner_id 外键 RESTRICT；CHAT/EMBEDDING 类型、协议、URL、Model ID、密文、配置 revision、测试状态/安全错误码/时间、实际 Embedding 维度、创建与更新时间；同一账号和类型唯一。不新增向量业务表。不更新原账号、知识库、文献、图片或原件。

可只读检查迁移（不显示任何密钥）：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
. .\scripts\Common.ps1
Import-MedicalRagEnvironment -RepositoryRoot (Get-Location).Path
docker exec medical-rag-postgres psql -U $env:DB_USERNAME -d $env:DB_NAME -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;'
if ($LASTEXITCODE -ne 0) { throw '只读迁移检查失败，保留错误，不清空数据库。' }
```

如果历史 checksum 异常，停止并保留错误，不执行 repair/clean 绕过。

## 3. 主密钥文件和备份

首次**保存非空 API Key**时，后端以 SecureRandom 生成 32 字节主密钥；只读取页面、登录、健康检查或保存无密钥配置不会生成文件。默认实际位置：

`C:\Users\Lenovo\Medical-RAG-System\secrets\model-master.key`

Linux 默认是仓库根目录的 `secrets/model-master.key`。与 PostgreSQL 分离，不能放入原件根目录或 frontend/src/target/静态资源目录。自定义 APP_MODEL_KEY_FILE 必须指向名为 secrets 的独立私有目录下 `.key` 文件；父目录应已经存在。不要把密钥放到公开共享目录。

加密：AES-256-GCM，每次随机 12 字节 nonce、128 位认证标签；格式 `v1:nonce:密文及标签`，附加认证数据绑定账号、模型类型与版本，复制密文到另一个账号/类型无法解密。数据库没有明文 API Key。Linux 新目录 0700、文件 0600，并校验实际权限/所有者；Windows 使用 ACL 与 `icacls.exe /inheritance:r` 限制为文件所有者，后续每次读取检查 ACL。无安全权限支持或设置失败则拒绝模型密钥操作，不降级公开权限。

只查看状态和 ACL，不读取文件内容：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
Test-Path -LiteralPath '.\secrets\model-master.key'
if (Test-Path -LiteralPath '.\secrets\model-master.key') {
    Get-Item -LiteralPath '.\secrets\model-master.key' | Select-Object Length, LastWriteTime
    (Get-Acl -LiteralPath '.\secrets\model-master.key').Access | Select-Object IdentityReference, FileSystemRights, AccessControlType, IsInherited
}
git check-ignore secrets/model-master.key
```

文件长度为32；ACL 不应允许其他账号读取。**不要 Get-Content、输出 Base64、上传主密钥或截图实际 API Key。** secrets/、*.key、data/、backups/ 被 Git 忽略。

首次保存成功后，用资源管理器把 `model-master.key` **复制**到自己受保护、加密且离线的备份位置，并单独管理数据库备份。备份目的地同样需要私有权限，不上传 GitHub/聊天。移动文件会使当前配置不可解密。仅备份数据库不能恢复 API Key；只备份主密钥也不能恢复资料。

主密钥缺失、短文件/损坏、权限不安全、错误主密钥或密文被篡改，测试会安全失败、不给出原始异常。如果数据库还有加密配置，缺失主密钥时不会生成替代文件；必须恢复与数据库匹配的备份。读取设置和本地文件管理仍可使用。确实永久丢失时，可在网页分别明确删除两类已保存密钥并保存，确认数据库已无密文后才能安全重新生成并录入新 Key；这属于恢复操作，不是无提示自动替换。

## 4. 网页实际验收

进入侧栏“系统设置”，地址 `/app/settings/models`。聊天和 Embedding 两张独立卡片，各自协议、Base URL、Model ID、密码输入框、保存及测试按钮。其他未实现模块仍显示待开发。

| 步骤 | 操作及应看到的结果 |
|---|---|
| 原资料 | 用原账号查看两个旧知识库、PDF、JPG/PNG、人工标签和备注；记录仍在，预览/下载正确 |
| 未配置 | 无密钥显示“未配置”，不能测试。两张表单初始 Key 都为空 |
| 录入聊天 | 选择 OpenAI-compatible，填写服务文档给出的 API 根路径（通常含 /v1）、聊天 Model ID、自己的 Key；点保存。显示“已配置，尚未验证”，Key 清空，不产生模型调用 |
| 录入 Embedding | 独立填写 Embedding 服务、Model ID、自己的 Key；可不同供应商，也可相同 Key；保存不改变聊天卡片 |
| 真实测试 | 分别点击“测试连接”。后端实际发送固定无医学短句；只有合规内容/向量响应才显示成功。可能产生少量 API 费用；Embedding 成功显示实际维度 |
| 失败独立 | 聊天测试失败只改聊天状态，Embedding 的结果保持，反之相同。错误仅中文安全说明，无服务商原始响应 |
| 空白保留 | 修改聊天 Model ID，Key 留空保存；旧 Key 保留，状态重新变成尚未验证。输入新 Key 则替换该类型 Key |
| 删除密钥 | 勾选“删除当前类型的已保存密钥”并保存；该类型未配置，另一个类型保留。普通空输入绝不删除 |
| 保存失败 | 如填写非法 URL，显示错误、保留输入，不出现保存成功；不能用旧测试成功表示编辑后的配置通过 |
| 刷新和登录 | 刷新设置页面、退出重新登录，Model ID/服务/状态仍在，Key 框始终为空 |
| 实际重启 | Ctrl+C 停前后端，用第2节两条启动脚本重新启动；原账号/资料/配置存在，重新登录，点击测试验证旧 Key 可解密；原主密钥不变 |
| 无效 Key | 暂用自己明确无效的测试 Key 保存并测试，应中文鉴权失败；随后自行恢复正确 Key，不发给开发助手 |
| 错误 Model ID | 暂填不存在的 ID；应模型不存在/无权或地址路径错误等安全说明，不伪成功；供应商错误编码不同，请核对真实返回行为 |
| 服务断线 | 暂填自己不可连接的合法服务地址或停止自己的受信任本机测试服务，测试失败/超时；仍能登录、切库、查看 PDF 和图片 |
| 两个入口 | 进入知识库详情，“进入文献管理”“进入医学图片”均为原图片入口风格的 Element Plus 按钮、无箭头，字体/边框/背景/高度/圆角一致；桌面并排、窄屏换行，跳转原列表 |
| 清理状态 | 若有既有删除待清理库，两按钮均禁用；不要为了测试故意破坏正式原件权限 |
| 笔记本 | 1024×768、1366×768检查两表单及入口无水平溢出，可正常纵向滚动 |

GET 安全检查：打开浏览器开发者工具 Network，刷新，查看 `GET /api/settings/models` 的响应，只能见类型/协议/URL/Model ID/两个布尔状态/测试状态、安全错误码/时间/维度/revision。不能有 apiKey、原密文、主密钥或解密 Key。初次**提交** Key 的 PUT 请求必然包含你刚输入的 Key，是同源输入行为；不要保存/分享该请求、HAR、日志或截图。浏览器不直接访问第三方模型 API。

匿名检查不用密码：

```powershell
curl.exe -i http://127.0.0.1:8080/api/settings/models
```

应401。带 Session 的缺/错 CSRF 写操作403，跨账号隔离由专用测试验证；不在个人数据库添加第二个管理员。

## 5. API、实际连接方式及安全边界

- `GET /api/settings/models` 返回当前管理员的两种配置；没有配置时返回两份未配置状态。
- `PUT /api/settings/models` 更新单一类型，JSON字段仅 type、protocol、baseUrl、modelId、apiKey、deleteKey。type=CHAT/EMBEDDING；protocol=OPENAI_COMPATIBLE。空/省略/null apiKey 保留旧密钥，deleteKey=true明确删除；不能同时删除与填写新 Key。拒绝未知字段、非法类型/长度/控制字符。
- `POST /api/settings/models/test` 仅 `{ "type": "CHAT" }` 或 EMBEDDING，测试已保存配置。全部需要 Session；写和测试需要 X-CSRF-TOKEN。
- Base URL 后追加 `/chat/completions` 或 `/embeddings`，**不要填完整终端路径**。聊天 `max_tokens=16`、非流式，验证非空 message.content；Embedding 请求 float 向量，检查单一 index=0、有维度且所有数值有限。
- 不返回聊天测试原文/向量，仅返回经过验证的配置状态、时间和 Embedding 维度。测试结果与 revision 绑定，期间保存新配置则旧结果丢弃。
- 总截止15秒、最多2个并发测试、响应最多256KiB；连接/读超时也受限。无自动重试、不跟随任何重定向、不携带浏览器 Cookie、不接收供应商 Cookie。
- 使用 Boot BOM 管理的 Apache HttpClient5 5.5.2 / HttpCore5 5.3.6（Apache-2.0），在实际 socket 连接所用 DNS 解析器中验证全部解析 IP，防止 DNS 二次解析绕过；HTTPS 默认验证证书及主机名。私网、元数据、保留地址拒绝，公网 HTTPS 和明确启用的本机字面地址除外。
- 不打印 Key、Authorization、外部响应和异常 cause；HTTP wire/header日志关闭。应用日志或数据库故障响应也不暴露密文。
- 当前模型客户端直接连接配置的 API，不读取浏览器代理/Session；需要专用企业出站代理的环境尚未实现模型代理配置。
- 兼容测试仅覆盖 OpenAI-compatible 的常见 chat/completions/embeddings 协议。仅支持 Responses API、特殊认证、强制其他请求字段的供应商可能不兼容；并非承诺所有 OpenAI-compatible 宣称都能通过。需用户按服务文档真实测试。

## 6. 全量自动化测试（隔离环境）

不会接触你的正式数据库、资料或主密钥；B1浏览器启动器给测试密钥分配自身随机临时目录，并且只在该测试实例启用回环 HTTP。专用本机 HTTP 服务使用合成 Key，**不是实际供应商或真实医学验证**。

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System\backend'
.\mvnw.cmd verify -Pintegration-test
if ($LASTEXITCODE -ne 0) { throw '后端失败，请保留 target 下的测试报告。' }
Set-Location '..\frontend'
npm.cmd ci
if ($LASTEXITCODE -ne 0) { throw '依赖安装失败' }
npm.cmd run test
if ($LASTEXITCODE -ne 0) { throw '前端单元失败' }
npm.cmd run build
if ($LASTEXITCODE -ne 0) { throw '生产构建失败' }
npx.cmd playwright install chromium
if ($LASTEXITCODE -ne 0) { throw '浏览器安装失败' }
npm.cmd run test:e2e
if ($LASTEXITCODE -ne 0) { throw '浏览器回归失败，保留实际错误。' }
# 必要时独立重跑 B1，不与上一个浏览器命令同时执行。
npm.cmd run test:e2e -- models.spec.ts
if ($LASTEXITCODE -ne 0) { throw 'B1 专项失败' }
Set-Location '..'
```

Linux 实际最终统计和开发中的失败记录见 [B1 验证记录](B1-验证记录.md)。Windows POSIX/符号链接测试可能跳过，应报告实际通过/失败/跳过数量；Linux结果不替代Windows NTFS/ACL/PowerShell实机。

测试结束保留正式容器和卷；只会清理测试启动器自己创建的随机容器、目录、进程。不要手工清理 data/、secrets/ 或正式卷。所有 B1 实机验收确认后再另行授权下一任务；当前停止，不进入 B2。
