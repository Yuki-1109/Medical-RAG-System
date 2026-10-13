# A8 阶段 A 综合验收（Windows 一次性操作）

本次交付分支 `codex/a7-a8-phase-a-acceptance`，从已验收 A6 开发。保留 `C:\Users\Lenovo\Medical-RAG-System` 的原 .env、mianmian、知识库、PDF、原件根目录、medical-rag-postgres 和命名卷。不重新初始化已有数据库，不运行 Flyway clean、docker rm 或删卷。自动化测试只重启/清理自建随机测试容器和目录，不操作个人数据。

## 1. 下载及一次性完整自动化测试

先在正在运行的前后端终端按 Ctrl+C，保存未提交的源码。打开 Docker Desktop，等待 Server 就绪，使用 Linux containers。以下在 PowerShell 直接复制；需要 JDK21、Node24（或22.13+）及 Git。Maven 使用仓库 Wrapper，不额外安装。

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
git status --short
# 有未提交源码时先保存，勿用 reset --hard 清空；干净后继续。
git fetch origin refs/heads/codex/a7-a8-phase-a-acceptance:refs/remotes/origin/codex/a7-a8-phase-a-acceptance
# 自动选择已有本地分支或建立跟踪分支，不合并 main。
git show-ref --verify --quiet refs/heads/codex/a7-a8-phase-a-acceptance
if ($LASTEXITCODE -eq 0) { git switch codex/a7-a8-phase-a-acceptance } else { git switch --track origin/codex/a7-a8-phase-a-acceptance }
git merge --ff-only origin/codex/a7-a8-phase-a-acceptance
git log -1 --format='%H %s'
git status --short --branch
java -version
node --version
npm.cmd --version
docker version
Set-Location '.\backend'
.\mvnw.cmd verify -Pintegration-test
if ($LASTEXITCODE -ne 0) { throw '后端测试失败，请保留终端和 target/failsafe-reports 报错，不继续声称通过。' }
Set-Location '..\frontend'
npm.cmd ci
if ($LASTEXITCODE -ne 0) { throw '依赖安装失败' }
npm.cmd run test
if ($LASTEXITCODE -ne 0) { throw '前端单元失败' }
npm.cmd run build
if ($LASTEXITCODE -ne 0) { throw '构建或类型检查失败' }
npx.cmd playwright install chromium
if ($LASTEXITCODE -ne 0) { throw '浏览器安装失败' }
npm.cmd run test:e2e
if ($LASTEXITCODE -ne 0) { throw '浏览器回归失败，请保留报错' }
# 全新隔离数据库的阶段 A 完整闭环，含 PostgreSQL 和前后端真实重启。
npm.cmd run test:e2e -- phase-a.spec.ts
if ($LASTEXITCODE -ne 0) { throw '阶段 A 综合闭环失败' }
# 真正使用生产构建资源验证 PDF.js worker 与图片，不连接本机个人数据库。
$env:MEDICAL_RAG_E2E_PRODUCTION = '1'
try {
    npm.cmd run test:e2e -- documents.spec.ts --grep 'real PDF upload'
    if ($LASTEXITCODE -ne 0) { throw '生产 PDF 浏览器失败' }
    npm.cmd run test:e2e -- images.spec.ts --grep 'real JPG/PNG'
    if ($LASTEXITCODE -ne 0) { throw '生产图片浏览器失败' }
} finally { Remove-Item Env:\MEDICAL_RAG_E2E_PRODUCTION -ErrorAction SilentlyContinue }
Set-Location '..'
```

测试容器名称仅为 `medical-rag-e2e-*` 或 Testcontainers 随机名，与正式容器分离。自动化有合成管理员密码，但它只在新隔离数据库中使用，不是 mianmian 的实际密码。HTTP测试控制仅监听回环随机端口，持随机令牌，不是产品 API；只接受受控 restart/restart-all，不能提交任意容器/PID/路径。真实重启前后核对 BCrypt、V5只执行一次，浏览器核对所有元数据和原件。

启动器会复制已构建jar到自建目录作为本次固定运行版本；按上面顺序先完成Maven，再执行浏览器。不要并行运行两个 Playwright 命令（共享 test-results），不要把测试日志/.cache/截图/合成产物提交 Git。Testcontainers/POSIX/符号链接条件在 Windows 可能受限；跳过或失败应按实际记录，不能套用 Linux 数字。当前最终 Linux 统计见 [阶段 A 最终记录](A阶段-最终验收记录.md)。

## 2. 启动已有个人环境（两个 PowerShell 窗口）

窗口一：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
docker start medical-rag-postgres
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
```

窗口二：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Frontend.ps1
```

浏览器打开 **http://127.0.0.1:5173**（若原 .env 自定义端口，以终端地址为准），用 mianmian 原密码。已有管理员应直接进入登录流程，**不出现要求重新创建管理员**。服务检查显示后端/数据库/pgvector/Flyway正常、迁移版本5；后端首次新增V5，原V1～V4不重跑。后端健康 http://127.0.0.1:8080/api/health 仅含安全状态，不含连接凭据。

## 3. 网页完整验收（仅删除新建专用验收库）

准备自己合成的英文PDF、JPG与PNG（例如文字PDF和两张色块教学图，勿用患者照片）。保留原文件。你此前已确认 A6 Windows PDF 原件/下载 SHA-256一致；本次重新验收新增图片和混合流程，不代表此前所有自动化日志都已确认。

| 步骤 | 操作与预期 |
|---|---|
| 原数据 | 先查看旧知识库及旧PDF，记录仍在、原件能预览下载 |
| 建两库 | 我的知识库→新建“A阶段验收K1”“A阶段验收K2”，真实ID不同；只用这两库做删除测试 |
| 上传 | K1进入文献管理上传英文PDF，再进入医学图片上传JPG及PNG；K2另上传PDF和PNG；都是真实列表与大小/页数/尺寸，图片默认未分类，文献仍待处理 |
| 原件 | 文献实际翻页、跳页；图片卡片显示真实色块/原图，进入详情；单独显示JPEG/PNG格式和人工类型 |
| 编辑 | 编辑K1简介；JPG详情选择病灶/正常/未分类，填Stage II、人工程度、关键词、备注并保存；刷新仍在，不声称AI已验证 |
| 同名 | 同名再次上传也有独立ID，不覆盖第一次原件；真实计数相应增加 |
| 下载 | 下载PDF/JPG/PNG，保存到独立验收下载文件夹，用下方Get-FileHash逐一比较，大小/SHA必须一致 |
| 切库 | 选择器切换K1/K2，顶部真实ID、列表和数量准确；把K1图片ID放进K2详情URL时拒绝显示串库图片 |
| 登录 | 刷新保持有效Session；退出、旧私有页面跳登录；原账号重新登录可看资料 |
| 重启 | 在后端和前端窗口Ctrl+C停止，再执行上面的两条启动脚本（保持正式数据库卷），原账号、库、文件、备注、哈希均保持；后端重启使旧Session无效，要重新登录 |
| 非法 | 空文件、假扩展名文件、损坏图片及超限图片中文报错，不形成ACTIVE成功记录；空库名/脚本备注拒绝；不要用巨幅患者图测试 |
| 单图 | 删除PNG先取消，数量/文件都不变；再次确认，只有这张消失、旧URL无效，PDF/JPG不受影响 |
| 单PDF | 新上传一份验收用重复PDF；删除先取消，再确认，只这篇消失，图片计数不变 |
| 联合库 | K1仍保留至少1PDF+1JPG/PNG，点击删库，确认窗实际列出PDF、图片及待清理数量；取消不发送DELETE、不变数据；再次确认K1及原件不可读取，K2所有资料/原件/计数保持 |
| 清理重试 | 如果实际出现占用/权限失败，关闭占用程序、恢复原目录权限后点重试；失败记录不可访问，库保留归属。不要手动删data来绕过 |

页码/图片ID属于网页URL，请从当前真实记录取得，不手写磁盘路径进 API。详情只显示原图；疾病分类、标注和双视图留C阶段，无图层就没有切换按钮。1024×768和1366×768检查主要操作无水平溢出，详情可纵向滚动。

### 原件 SHA-256（直接路径例子，不使用交互提示）

把下面的示例路径换成你的原件和下载路径，文件名不同不影响哈希。下载应另存文件夹，避免与原件同目录同名覆盖：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\Users\Lenovo\Documents\A阶段验收原件\research.pdf'
Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\Users\Lenovo\Downloads\A阶段验收下载\research.pdf'
Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\Users\Lenovo\Documents\A阶段验收原件\teaching.jpg'
Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\Users\Lenovo\Downloads\A阶段验收下载\teaching.jpg'
Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\Users\Lenovo\Documents\A阶段验收原件\teaching.png'
Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\Users\Lenovo\Downloads\A阶段验收下载\teaching.png'
```

每一对 Hash 相同，网页大小和哈希也对应；人工修改前后重复下载仍相同。不要把原件或 Hash/账号相关截图公开上传。

### 权限隔离验证

无Cookie PowerShell GET无需真实密码：

```powershell
curl.exe -i http://127.0.0.1:8080/api/knowledge-bases
curl.exe -i http://127.0.0.1:8080/api/images/1/content
curl.exe -i http://127.0.0.1:8080/api/documents/1/content
```

匿名均应401（认证先于ID检查）；无会话写请求至少被拒绝，带Session但缺/错CSRF返回403。网页未登录私有URL跳登录。跨账号读取/改/删及名称/哈希不泄露由独立 ImageIT/DocumentIT/KnowledgeBaseIT 真实测试，**不在个人DB创建第二管理员或移交库归属**。同一管理员可以拥有K1/K2并下载两库自己的资料，这不是“跨账号”；当前库列表/详情仍必须匹配URL库。

## 4. 故障处理与安全停止

- Docker Client/Server缺一：等待Docker Desktop、确认Linux containers，勿重建实际数据卷。
- Maven依赖失败：检查网络/JDK21和原Wrapper，保留报错；不是DB删除理由。
- Node模块/worker失败：frontend执行npm.cmd ci/build；浏览器安装按前面命令；PDF.js资源只从本机依赖加载。
- V5校验失败：核对分支、健康/后端日志，旧迁移不得手工修改。Flyway clean-disabled保持开启。
- 原件读不到：检查原APP_STORAGE_ROOT与文件权限/备份，不更改为空目录，不篡改DB路径/哈希。
- Windows临时日志共享错误或权限测试跳过：提供实际failsafe报告；现有A4 RestartTestApplication仍保留等待子进程与日志关闭再清理的机制，不吞异常。
- 不运行自动化数据库重启命令去操作正式容器；A8自动重启仅测试随机容器。正式库本次网页验收重启前后端即可，不要求停止个人DB。

结束在前后端终端Ctrl+C。可保持正式Docker数据库运行；不删除容器/卷。只删除自己刚建的验收库，不动旧库；有需要保留验收材料则保留K2。把实际终端测试统计、待验收清单和错误反馈给开发助手；Windows未执行的项目仍写“待验收”。验收确认并另行授权之前不进入B1。
