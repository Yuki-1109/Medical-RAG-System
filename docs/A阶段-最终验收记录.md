# 阶段 A 最终验收记录

日期：2026-10-14（Asia/Shanghai；原Linux日志UTC为2026-10-13）。交付分支：`codex/a7-a8-phase-a-acceptance`，基于已验收A6 `7567c0391b8d6844364f9f0759896ce3a82d1d0c`，正常Git历史保留；最新提交以 `git log -1 --format=%H` 和最终中文交付报告为准（不把包含本文件的自引用SHA写回文件制造无限新提交）。不合并main、不发布Release、不进入B1。

## A1～A8交付范围

| 阶段 | 实际功能 | Windows状态 |
|---|---|---|
| A1 | Vue/Vite/Element Plus、Java21/Boot、真实PG/pgvector、Flyway、健康/API/Wrapper/脚本 | 用户确认 |
| A2 | 一次管理员、并发初始化限制、BCrypt、Session、CSRF、登录退出/权限/重启登录 | 用户确认 |
| A3 | 中文初始化/登录/布局、守卫、刷新有效会话/失效跳登录、中文错误、HttpOnly | 用户确认 |
| A4 | 多知识库真实CRUD、归属/当前库、确认删除、V3、安全隔离 | 用户确认；此前日志清理修复完整53项确认 |
| A5 | 私有根目录、流式大小/SHA、格式/路径/链接防护、原子发布、只读句柄与精确清理 | 用户确认 |
| A6 | V4 PDF上传/真实页数/待处理、PDF.js本地worker翻页、私有原件下载、生命周期/重试/非空库清理 | 用户核心验收及Get-FileHash原件一致确认；不虚报未提供的Windows自动化数量 |
| A7 | V5 JPG/JPEG/PNG上传/完整解码/尺寸/像素限制、原图/EXIF不变、人工基础信息、私有列表/详情/下载/重试、图片及PDF联合计数/清理 | Linux专项通过；Windows待实机 |
| A8 | 全新环境闭环、真实PG/前后端重启、BCrypt/元数据/原件哈希/隔离/异常回归、一次性Windows说明 | Linux独立闭环通过；完整浏览器29通过，生产专项结果见下；Windows待实机 |

当前可交付的是阶段A资料管理骨架，**不是整个RAG MVP**。B的模型/密钥/文本提取/Chunk/Embedding/向量检索/问答/证据不足拒答/引用会话尚未实现；C的疾病三级分类/搜索/标注/独立原图-标注图切换尚未实现；D/E增强、整套Compose/Release/演示库分发未实现。PDF仅真实存储及页数预览，处理状态PENDING，不虚构索引、处理进度或医学判断。

## 原资料和迁移保护

- 原需求blob `62b76ff8461dd2858ee6c0667709325939cbc682`，计划blob `1112a07276cecfc02c1d318e3b43afb6e37a1549` 均与A6相同；V1～V4逐个Git blob一致。用户mianmian/.env/个人PostgreSQL容器、卷和原件未操作。
- V5只创建medical_images；旧账号、库、PDF行与原件不迁移/重写；RESTRICT外键，不级联。真实V4→V5测试保留所有旧行、密码哈希、旧SQL checksum、PDF字节，重复migrate为0。
- 仅ACTIVE计入有效文献/图片，非ACTIVE合并待清理。删除确认含三种真实数量；先独立锁库，再用新的SQL快照读取影响，等待上传锁不能拿旧快照删新资料。
- 文件与DB不是同一原子事务：先登记上传/删除意图、再操作受控原件；失败保留不可访问记录、定位凭据和可重试状态。库归属保留至PDF/图片全部清理完成。不会扫删未知文件。

## Linux最终执行结果

环境JDK21.0.8/Maven3.9.11/Node24.19/npm11.9/Docker28.4/PG17.10/pgvector0.8.2/Chromium。LinuxMaven使用/tmp环境专用settings、CA/JDK/依赖缓存，浏览器指定已安装系统Chromium；它们不入Git。命令的仓库标准部分如下：

| 命令 | 实际结果 |
|---|---|
| backend `./mvnw verify -Pintegration-test` | **72单元 + 133真实PG集成 = 205通过**；0失败/错误/跳过，BUILD SUCCESS，约2分37秒；包含旧144与新增61（图片验证10、ImageIT50、V5迁移1） |
| frontend `npm ci` | 锁定依赖安装成功，依赖及版本未更换 |
| frontend `npm run test` | **41通过**（旧35 + 新6），0失败 |
| frontend `npm run build` | vue-tsc类型与Vite生产构建通过，本地PDF.js worker/字体/CMap/WASM保留 |
| frontend `npm run test:e2e -- images.spec.ts` | **5通过**，0失败/跳过，27秒；真实JPG/PNG像素、人工编辑、下载hash、取消/确认、串库保护、会话失效、1024/1366布局 |
| frontend `npm run test:e2e -- phase-a.spec.ts` | 独立全新数据库完整闭环 **1通过**，0失败/跳过；真实管理员UI创建、5文件、人工编辑、退出、真实PG/前后端重启、重新登录核对、K1删除/K2保留 |
| frontend `npm run test:e2e` | **29通过**（原23 + A7五项 + A8一项），0失败/跳过，3.1分钟；含A6生产同类PDF完整流程与A8实际DB/前后端重启 |
| 生产PDF：`MEDICAL_RAG_E2E_PRODUCTION=1 npm run test:e2e -- documents.spec.ts --grep 'real PDF upload'` | **1通过**，0失败/跳过，15.7秒；真实Vite preview及构建worker，翻页/跳页/原件hash/重启/非空删除 |
| 生产图片：`MEDICAL_RAG_E2E_PRODUCTION=1 npm run test:e2e -- images.spec.ts --grep 'real JPG/PNG'` | **1通过**，0失败/跳过，7.3秒；真实Vite preview、JPEG/PNG像素、人工保存/刷新/hash/切库/确认及URL释放 |
| Linux PowerShell7.5.4 | 4份脚本语法解析通过；新建专用.env夹具验证图片配置加载、宽度0拒绝及自身清理；不是Windows PowerShell/NTFS通过 |

浏览器专项/生产专项重复完整流程，不累计为额外独立测试数量。完整回归保留原A6 23浏览器/35前端/144后端；原stage预期仅更新迁移版本5、未开发导航5→4、未来未配套表从medical_images→document_chunks、旧V3→V4迁移显式target4，不删/跳原安全断言。当前数据库无未来业务表。

## A8完整闭环的真实证据

独立启动器新建随机PG容器、根目录与fixture目录、端口，固定本次jar副本，确认未初始化后才运行首次创建。浏览器实际上传K1的PDF/JPG/PNG和K2的独立PDF/PNG；预览真正PDF canvas与图像、保存简介/分期/程度/关键词/备注、记录所有大小/hash/元数据。退出后测试控制真实停止前后端、自建PG容器停止/启动（检查Running=false）、等待数据库、恢复新端口、启动前后端；SQL前后BCrypt不变、V5成功记录始终1。

重新登录原测试管理员，账号ID、两库、全部元数据逐项一致，每份下载与原件逐字节/hash相同；K1/K2列表仅各库ID。匿名内容/列表401、缺/错CSRF403、额外owner字段400；取消单PDF/单图不发DELETE，确认单图不影响PDF/JPG。混合库确认显示真实影响，先取消、再确认K1不可读；K2原件/hash/计数保持。最后只清理本次库、进程、目录、随机容器与匿名卷，未动正式medical-rag-postgres。

跨账号修改/读取/删除、PG trigger保存失败、文件清理占用/权限故障、损坏hash、上传中断、DB删除失败后重试、混合库失败及重试、两方向上传/库删除与编辑/下载/删除并发由真实隔离IT覆盖；人工病灶类型不代表医学验证。文件占用为明确故障注入，未冒充NTFS原生共享锁。

数量快照回归以真实latch/事务协调，并在临时恢复旧查询时实际失败（旧确认未抛冲突）；恢复修复后完整205通过。开发中有定位失败和一次并行重建jar的浏览器干扰，修复与最终复测记录见[A7验证](A7-验证记录.md)，不能把这些首次失败/未运行计为通过。

## 最终结论

阶段A代码实现和Linux自动化达到交付目标，前后端与隔离真实PG/pgvector确实运行；最终后端205、前端41、完整浏览器29、独立全新闭环1、生产专项2全部通过，无未修复的Linux阻塞失败、无最终跳过。原Windows数据未被操作，Windows新增阶段仍待实机。

## 未执行、限制与恢复

- **Windows A7/A8未执行**；完整自动化、V5个人旧库升级保留、JPG/PNG原件哈希、中文/空格路径、NTFS权限/原子移动/文件占用/句柄/联接点与普通笔记本实机需用户验收。依照[A8一次性说明](A8-阶段A综合验收.md)执行，不再零散索取日志。
- POSIX/符号链接权限受限时按实际Skipped及原因报告，不把Windows统计写成Linux全通过。Linux全部这些场景实际执行。
- 根目录应仅可信本机账号可写；不是恶意本机管理员隔离或病毒扫描。数据库/磁盘损坏需完整备份恢复，上传中断或清理失败保留记录供显式重试，不清空个人目录/卷。
- 当前缩略图来自私有完整原件，每页最多12张并释放URL；没有独立衍生缩略文件、Range流媒体、巨幅/DICOM支持。尺寸与总像素限制在解码前生效，旧.env无需改。
- Windows若失败，保留错误类别和测试统计，关闭占用程序/恢复原目录权限后重试；不修改历史迁移、不重置密码/卷、不手工改文件hash绕过验证。

最终Git提交前检查不包含真实.env/密码/Key/数据库/个人原件/截图/日志。完成推送后停止；待用户Windows验收及明确新授权再开始B1，仅建议聊天/Embedding模型安全配置、Key加密和真实连接测试，不提前实现。
