# Windows 本地统一入口候选

统一调用方式（PowerShell 7）：

```powershell
./Invoke-IasLocal.ps1 -Action Validate -ConfigPath '<外部受限发布配置>'
./Invoke-IasLocal.ps1 -Action Status -ConfigPath '<外部受限发布配置>'
./Invoke-IasLocal.ps1 -Action Initialize -ConfigPath '<外部受限发布配置>' -ExclusiveWindow
./Invoke-IasLocal.ps1 -Action Start -ConfigPath '<外部受限发布配置>' -ExclusiveWindow
./Invoke-IasLocal.ps1 -Action Stop -ConfigPath '<外部受限发布配置>' -ExclusiveWindow
./Invoke-IasLocal.ps1 -Action Backup -ConfigPath '<外部受限发布配置>' -TargetDirectory '<全新备份目录>' -ExclusiveWindow
./Invoke-IasLocal.ps1 -Action Restore -ConfigPath '<外部受限发布配置>' -TargetDirectory '<备份目录>' -RestorePrefix 'ias_restore_demo' -ExclusiveWindow
```

变更命令可使用 `-WhatIf` 预览，必须由当前运行窗口所有者执行。启动期间发生失败时已启动服务保留，按新 runtimeManifest 核实并停止，避免误杀其他项目。停止先核对所有进程的 PID、创建 UTC、完整 -jar 参数和磁盘 SHA，再执行。禁止 8081。MySQL 常驻服务不由 Stop 停止。

发布配置从 `local-release.example.json` 创建到受限本地目录；模板刻意不可直接执行。services 填写最终统一 manifest 的八项 `{role,port,jar,sha256}`，顺序由工具固定。runtimeManifest 必须为新文件，绝不能复用技术负责人仍在使用的窗口 manifest。launcher 引用既有外部环境工具的 Start-LocalChainService.ps1 并固定 SHA；它继续加载受限配置，不将密码放在参数中。migrations 填写已审查的有序 `{script,sha256}`；迁移适配器必须自行支持重入并报告失败。不得将空 migrations 当作“已初始化”。

`runtimeReferences` 显式记录环境脚本、八个角色配置所在目录、MySQL 和领域服务受限配置文件的绝对路径；环境脚本还需固定 SHA。统一启动将这些**路径引用**传给启动器，不依赖当前终端预先设置 `IAS_*` 环境变量，密码仍只从受限文件读取。修改任何引用前须检查实际文件和访问权限。

`mysqlServer` 必须固定本机项目专用 MySQL 的服务端和配置文件路径及 SHA-256。初始化或启动时，若 3307 未监听，脚本会启动该专用实例并等待最多 120 秒；若已有监听，必须先核验数据目录。启动前还会检查旧运行清单：仅当清单早于本次开机、端口空闲且进程不存在或能通过创建时间证明 PID 已复用时才归档，归档文件会保留；无法判断时需要人工检查。

每个迁移条目还须声明 `schemas` 列表，所有条目合计必须准确覆盖七个指定业务库且不得重复；迁移回执也须报告对应的同一组库。该校验用于防止配置漏项，不替代核验迁移实际执行、权限及版本一致性。

`Invoke-IasMigrations.ps1` 和 `migration-adapter.example.json` 是迁移适配器候选：需预先提供固定 JAR、Java、全部迁移文件的指纹和受限账号引用。它检查文件清单，连接时校验 3307 数据目录，然后运行 Flyway 校验及二次迁移检查；尚未在真实 MySQL 上验证，也不会创建账号或授予运行权限。不要将示例配置当作可直接执行的发布配置。

备份要求所有项目服务端口均已停止和独占 3307、私有文件窗口。只备份列明的七 schema 与 privateFileRoots；备份目录移除继承 ACL 后只授权当前用户。MySQL option 文件由运维在受限目录提供，仅引用路径，内容不进入仓库、日志或参数。dump 数据和附件本身敏感，不进 Git。完成标记包含逐文件 SHA 和统一 Git SHA。任何失败保留不完整目录，不生成完成标记。

初始化、备份和恢复会使用固定SHA的MySQL客户端通过受限option文件只读查询3307的 `@@datadir`，必须与 `C:\ias-local\Develop\IAS-MySQL84\data` 一致才继续。数据库无法查询或目录不匹配时失败关闭。此检查不替代独占窗口、账号最小权限及真实恢复测试；当前仅有离线校验，尚未在统一发布配置上实测该查询。

恢复只创建全新 `ias_restore_*` schema，不覆盖正式测试库；文件也复制到全新恢复目录。部分失败保留新目标，不自动删除或重试。恢复命令不切换线上数据源。必须由技术负责人明确检查迁移/授权、对象 DEFINER 与 schema 引用，并通过恢复后数据库事实和浏览器验收，再制定独立的切换适配器。当前是隔离恢复候选，不能宣称已完成生产切换/业务恢复。

`Prepare-IasRestorePrincipals.ps1` 可为七个已恢复的隔离库创建全新的独立账号，按对应原运行账号的 schema、表和列授权逐项复制；它不修改原账号，拒绝源账号的全局或角色继承权限及未知授权。密码随机生成并仅写入受限 secrets 目录的全新文件；中途失败时保留该文件和可能已创建的账号，必须先查明结果，不可直接换名重试。`Invoke-IasRestoreReview.ps1` 检查目标表和列结构、跨库外键及不支持的视图/触发器/例程/事件，比较七组授权且拒绝恢复账号额外权限，并校验附件清单。它不切换服务、不校验恢复前后业务记录内容，也不替代服务启动与浏览器验收。

SQL预检采用保守文本检查：发现带反引号或不带反引号的限定名（例如 `otherdb.sample`）、DEFINER、建库或切库语句时拒绝导入。这也可能拒绝合法的表别名、注释或字符串，遇到时需人工审查并采用经过验证的隔离恢复流程；它不是完整SQL解析器，也不能代替数据库最小权限和真实恢复验证。

当前候选边界：Start 核对八服务监听及制品身份，并调用 HTTP 和 TLS 适配器；`Invoke-IasTlsProbe.ps1` 和 `tls-probe.example.json` 提供候选实现，按固定角色调用各服务的认证探测，使用受限配置中的现有信任库；需引用包含最新 trust 的统一构建并进行真实 TLS 联调。它只核验 TLS 与角色，不检查数据库或业务，监听和认证探测均不能单独证明业务就绪。固定 dist 服务已接入启动、HTTP 首页检查和进程归属记录，但尚未真实联调；需固定两份 dist 配置及文件指纹。发布配置及初始化适配器尚未就绪，真实备份恢复与干净环境演练仍待统一发布制品后集成。旧轮换私密备份不在任何删除或恢复操作范围。

静态服务按前端构建的 `/staff/`、`/customer/` 基础路径提供文件，并将另一个入口的页面请求转发到对应本地服务，使入口跳转保留当前浏览器来源。使用入口为 `http://127.0.0.1:4173/staff/` 和 `http://127.0.0.1:4174/customer/`；静态服务不接受 localhost Host。本地身份启动配置和 trust 源码已加入这两个来源，但服务需随统一发布制品重建才会生效。当前两份 dist 已在临时静态服务上验证两端互访和 HTML 引用的资源指纹；仍须在统一发布环境进行浏览器、登录及 API 联调验证。

恢复导入前会检查完整的七库清单和每份 dump；发现库切换、数据库创建或删除、DEFINER、可疑的库限定名时会在创建恢复库前拒绝执行，保留备份供审查。这是保守筛查，可能拒绝合法 SQL，也不构成完整 SQL 解析或隔离证明；尚需独立审查 schema 引用、对象定义和权限，再执行真实恢复验证。

验证：`./Test-LocalLifecycle.ps1` 与 `./Test-IasMigrationAdapter.ps1` 为离线检查，后者使用模拟迁移执行器；`node --test ./serve-dist.test.mjs` 除临时端口测试外会短暂绑定 4173 和 4174 检查真实 dist，端口占用时会失败，不停止现有进程。`Test-IasTlsProbe.ps1` 在确认 8441 空闲后以合成证书短暂启动本次创建的夹具，需传入包含最新 trust 的 ticket 候选 JAR 和全新证据目录；该夹具不验证真实服务或项目证书。当前不得在技术集成窗口运行 Initialize/Start/Stop/Backup/Restore。
