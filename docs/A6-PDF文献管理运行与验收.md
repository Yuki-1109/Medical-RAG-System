# A6：PDF 文献管理运行与 Windows 验收

日期：2026-10-14（Asia/Shanghai）。基线为已验收 A5 提交 4a93e0ee5602ca22d3df416e68522184c4f2837a，开发分支 codex/a6-pdf-document-management。A5 Windows 验收由用户确认符合要求；A6 的 Windows 结果须另行验证。

## 升级前的保护原则

保留 mianmian、原 .env、原有知识库、medical-rag-postgres、medical-rag-pgdata 和 data/originals（或原自定义目录）。本次不重新初始化管理员，不运行 Flyway clean，不删除数据库/卷，也不覆盖环境文件。先在应用终端按 Ctrl+C 停止旧前后端，保留数据库；如需备份，使用你原有的备份方式同时保护数据库与原件，不在 Git 中保存备份。

需要 JDK 21、Node.js 24 LTS（也支持 22.13 及以上的 22 LTS）、Git、Docker Desktop 的 Linux containers。PDF.js 的版本要求使 Node 22 最低版本从 22.12 调整为 22.13；已有 Node 24 不需要更换软件。

## 一组命令：下载、更新依赖及完整测试

打开 PowerShell，以下命令用于第一次获取 A6 本地分支。git status 若有你自己的代码修改，先保存，不能强制重置；不要删除未跟踪的私人资料。

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
git status
git fetch origin codex/a6-pdf-document-management
git switch -c codex/a6-pdf-document-management FETCH_HEAD
git branch --show-current
git log -1 --format="%H %s"

java -version
node --version
docker version

Set-Location backend
.\mvnw.cmd verify -Pintegration-test
Set-Location '..\frontend'
npm.cmd ci
npm.cmd run test
npm.cmd run build
npx.cmd playwright install chromium
npm.cmd run test:e2e
Set-Location ..
```

若已有本地 A6 分支，下载部分改为 `git switch codex/a6-pdf-document-management` 后 `git merge --ff-only FETCH_HEAD`。git log 输出应与本次交付报告一致；不合并 main。本阶段新增 PDF.js 依赖，**即使已有 node_modules 也要执行 npm ci**，否则旧安装中没有预览器。

docker version 应包含 Client/Server。测试使用独立 Testcontainers 或测试启动器的临时 PostgreSQL、原件根目录及端口，不接触你实际的管理员、知识库和原件。浏览器启动器先建立全新的空测试数据库，再首次创建合成账号；不要绕过启动器直接对正式网站执行 setup 用例。

Maven 最后必须有 BUILD SUCCESS、Failures/Errors 为 0；失败时会显示 BUILD FAILURE 和报告位置，不要只看某一组通过。普通 verify 不执行数据库集成测试；本节带 -Pintegration-test 才是完整后端验收。A5 的 POSIX 专项在 Windows 不适用，符号链接测试可能因权限受限明确跳过；这类结果须按原因反馈，不能计为已通过。A6 的上传、下载、删除、权限与重启场景不能跳过冒充成功。前端应显示单元全部通过、类型/构建成功、浏览器测试全部通过；当前实际计数见 A6 验证记录。

## 启动已有本地网站

Docker Desktop 就绪后，第一个 PowerShell：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
docker start medical-rag-postgres
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
```

第二个 PowerShell：

```powershell
Set-Location 'C:\Users\Lenovo\Medical-RAG-System'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Frontend.ps1
```

打开 **http://127.0.0.1:5173**，用原 mianmian 账号登录；如本机 .env 已改端口，使用实际前端端口。后端应出现 Started MedicalRagApplication，首次升级只执行 V4；再次启动不重复迁移。服务检查页四项正常，Flyway 版本 4。

原 .env **无需新增必填项**。沿用 APP_STORAGE_ROOT、APP_STORAGE_MAX_PDF_BYTES；默认根目录为项目 data/originals，PDF 上限 52428800 字节（50 MiB）。仅有需要才在原 .env 追加缺少的可选项，已有项只编辑该行，不能用模板覆盖原文件。绝对 Windows 路径建议使用正斜杠、中文/空格均可；更换根目录不会搬迁旧原件，不能随意切到空目录或删除旧目录。

## 一次完成整套网页验收

请选择自己制作或有使用许可的 PDF，至少一份有三页的英文文字版。不要用真实患者资料做测试，也不要将 PDF 放进 Git 跟踪的源码目录。以下删除操作仅针对**专门新建的 A6 验收库**。

1. 在“我的知识库”创建“A6 验收 K1”和“A6 验收 K2”，记下两个真实 ID。进入 K1，点击“进入文献管理”；左侧“文献管理”也可进入当前库，没有选择库时先选库。
2. 选择 PDF，点击“开始上传”。应显示真实传输进度；100% 时仍可能显示“正在校验并保存”，最终成功才刷新列表。列表应有原文件名、实际大小、3 页（按你的原件为准）、时间和“待处理”。该状态不代表文字提取或索引成功。
3. 点击“查看原文”，确认是真正 PDF 页面；点击下一页、上一页，输入 3 跳转。网址可使用 `.../documents/<文献ID>?page=3`；输入超范围页码会被控件限制，直接使用无效 page 参数会有中文提示并安全回到第一页。
4. 点击“下载原件”，检查中文/英文/空格文件名正常。用下面命令比较上传前文件和下载文件 SHA-256；两条 Hash 必须一致。
5. 刷新预览/列表，文献仍在。后端窗口 Ctrl+C 后重新运行 Start-Backend.ps1；页面重新核验会话并要求登录，用原账号重新登录，原件、页数和页码预览仍可用。
6. 在顶部切换 K2，应进入 K2 的文献列表，没有 K1 的文献。给 K2 上传另一份测试 PDF，然后切回 K1，确认各库数量独立。回到知识库列表/详情，应显示真实有效文献数。
7. 将同名 PDF 再上传一次，出现两个不同文献 ID，两个都可下载，互不覆盖。
8. 删除 K1 的一篇文献时先取消，列表、数量不变；再确认，只删除指定文献。其详情/原文/下载旧地址应失效，另一篇及 K2 均正常。
9. 删除仍含 PDF 的 K1，确认弹窗应展示真实有效文献和待清理记录数量。先取消，资料保留；再确认，知识库与关联文献失效、原件清理，当前库不继续有效；K2 的文献、数量和下载不受影响。
10. 上传一个空 PDF、改扩展名的文本或损坏 PDF，应中文报错，不生成有效卡片或增加文献数。需要密码而无法打开的 PDF，应提示先本机解密另存。有效扫描 PDF 可保存，仍是“待处理”，不声称 OCR 或文本处理完成。
11. 退出登录后访问文献深链，应跳转登录；重新登录恢复合法路径及 page。未登录请求 /api/documents/<ID>/content 必须拒绝，不应在 /uploads/** 下公开原件。
12. 测试结束，可保留 K2 的合成文献，或者通过页面二次确认删除该测试库；不要删除已有正式研究库。一次完成后反馈结果与异常即可，不需要逐步发送截图。

比较哈希时，PowerShell 会提示输入两份 PDF 的实际完整路径；本机路径只用于本机校验，不提交 Git：

```powershell
$originalPdfPath = Read-Host '请输入上传前 PDF 的完整路径（不要加引号）'
$downloadedPdfPath = Read-Host '请输入下载 PDF 的完整路径（不要加引号）'
Get-FileHash -LiteralPath $originalPdfPath -Algorithm SHA256
Get-FileHash -LiteralPath $downloadedPdfPath -Algorithm SHA256
```

跨账号归属测试在后端隔离数据库中完成；不用在真实数据库创建第二管理员。在网页的两个自建库中检查跨库列表即可，其他账号夹具只由自动化事务建立并回滚。

## 数据、接口与生命周期

V4 新增 documents，包含知识库归属、原名、服务器存储标识、实际大小/哈希/页数、处理状态、独立生命周期、清理错误及时间。knowledge_bases 仅增量增加 deletion_pending；V1～V3、users、原有知识库不重建。外键为 RESTRICT，没有 ON DELETE CASCADE。所有 ID 在 JSON 中为十进制字符串；响应不包含 storageKey、磁盘路径、owner_id 或密码。

| 接口 | 行为 |
|---|---|
| POST /api/knowledge-bases/{id}/documents | 单 file multipart 上传，Session/CSRF、归属、大小及实际格式校验 |
| GET /api/knowledge-bases/{id}/documents | 当前所属库的真实记录；未完成/清理失败记录独立标识 |
| GET /api/documents/{id} | 有效文献详情；不存在/非归属同一安全 404 |
| GET /api/documents/{id}/content、/download | 查询 DB 文献归属后，由 A5 核验大小/SHA-256，流式提供未改写原件 |
| DELETE /api/documents/{id} | 提交删除状态，清理原件再移除记录；失败保留凭据，可重试 |
| GET /api/documents/upload-limits | 认证后返回真实 PDF 大小配置，不展示虚构限制 |

处理状态预留 PENDING/PROCESSING/INDEXED/FAILED；本阶段上传成功只为 PENDING。生命周期 UPLOADING/ACTIVE/DELETING/DELETE_FAILED 与处理状态分开：只有 ACTIVE 能预览/下载并计入 documentCount，其余计入 pendingCleanupCount，可在列表明确清理或重试。

上传先在 PostgreSQL 提交 A5 生成的 UUID 定位意图，再锁定知识库写入、核验哈希、PDFBox 严格检查 Catalog/页树/页数并激活记录。失败时清理该意图；清理失败保留原定位记录，不返回成功。最多两个并行校验任务，页数 1～10000；PDFBox 读取使用 8 KiB 随机访问缓存，流缓存超出 16 MiB 使用其可关闭的临时缓存，不把原件全部加载到后端堆内存。

删除单文献先提交 DELETING，再在库锁下清理，最后移除记录。删除非空库先提交 deletion_pending 和关联文献失效状态，保留库归属到清理完成；成功清理各文献后才删库。失败时返回实际错误，重试只处理剩余受控对象。文件已删但数据库删除失败时，记录仍不可访问，下次重试可确认原件已不存在后完成清理。

上传与删除使用同一知识库行锁，并重新核验删除标志，避免并发留下孤立文件。网页删除确认还携带看到的数量；若确认期间数量变化，后端拒绝本次删除并要求再次确认。A7 可通过 KnowledgeBaseMaterialCleaner 接入真实图片清理；当前图片表不存在，imageCount 真正为 0，出现不匹配的图片表时明确拒绝，而非伪报 0。

PDF 下载/预览为 application/pdf、no-store/private、nosniff 和安全 Content-Disposition。当前全文件 HTTP 200，Accept-Ranges: none，PDF.js 禁用 Range；没有声称实现字节范围下载。浏览器下载额外核验大小/SHA-256。预览仅渲染 PDF 页面，不运行脚本或点击文档外部链接，不提供假截图；worker、CMap、字体及 WASM 均为本地依赖资源，退出/切换释放渲染任务和 worker。

## 常见问题与未实现边界

| 情况 | 操作 |
|---|---|
| 找不到 pdfjs-dist / 预览依赖 | 停前端，在 frontend 执行 npm.cmd ci，确认 Node 24 或 22.13+ |
| V4 校验/迁移失败 | 保留原数据库和 .env，核对实际代码/日志；不修改 V1～V3、不 clean/删卷绕过 |
| PDF 过大或页数过多 | 默认 50 MiB、1～10000 页；适当拆分原件，或安全调整原有大小配置 |
| 密码 PDF / 结构损坏 | 先在本机用可信阅读器核查，密码文件另存为无需密码的合法 PDF 后上传 |
| 清理失败 / 原件被占用 | 关闭占用程序、恢复原目录权限，点击“重试清理”或重试知识库删除；不要清空 data |
| 下载哈希不一致 / 原件丢失 | 界面明确失败；检查原 APP_STORAGE_ROOT 与备份，不通过直接改数据库路径伪装修复 |
| 删除确认提示数量变化 | 刷新真实数量并重新确认；本次尚未执行删除 |
| PDF 预览或 worker 加载失败 | 查看中文提示、后端健康和浏览器 Console；保留错误反馈，执行 npm ci/build，检查本机代理，worker 不依赖 CDN |
| backend 重启后跳登录 | A2 设计要求，原数据库账号仍在；正常重新登录 |
| 测试 Docker 无法访问 | 先等待 Docker Desktop Server 就绪、使用 Linux containers，不改动实际数据卷 |

本阶段不做全文提取、医学清洗、Chunk、Embedding、向量检索、RAG、OCR、重新解析按钮或医学图片。扫描 PDF 可存储不代表已提取文字；B2 才做文本提取/清洗/分块及失败重试，B3 才做向量索引。

根目录仍须只由可信本机账号写入；A5 路径校验不能替代针对恶意本机管理员的 OS 隔离或病毒扫描。文件和数据库不是同一个原子事务，崩溃可能留下已登记的 UPLOADING/DELETING 记录，恢复后由用户明确清理或重试；不会自动扫描删除未知目录。数据库或磁盘硬件损坏需完整备份恢复，不能保证断电情况下所有存储设备的原子性。

实际 Linux 结果及 Windows 待验收项目见 [A6 验证记录](A6-验证记录.md)。完成 A6 提交推送后停止，A7 必须另行授权。
