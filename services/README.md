# 本地四服务链（第九批取消）

前八批联合结论见技术评审33及各角色最新记录；当前第九批普通取消按任务11推进，接口以开发者设计/登录与会话最小契约草案.md第19节B9-1为准。已有人工受理/分派/处理/解决确认/关闭/补充资料及私有附件；第九批自验、前端页面和独立联合验收分别记录，不据源码存在推定通过。

## 启动

Java21/Maven3.9.16/Boot4.0.8；gateway Cloud2025.1.3/Gateway Server Web MVC5.0.3。trust普通共享JAR。gateway无DB；identity/asset/ticket各独占既有3307的ias_identity_test/ias_asset_test/ias_ticket_test，运行不自动迁移或建账号。

工作根目录四个独立PowerShell终端分别运行：

```powershell
& '.\环境工具\Start-LocalChainService.ps1' -Service identity
& '.\环境工具\Start-LocalChainService.ps1' -Service asset
& '.\环境工具\Start-LocalChainService.ps1' -Service ticket
& '.\环境工具\Start-LocalChainService.ps1' -Service gateway
```

端口127.0.0.1:8441/8442/8443为identity/asset/ticket TLS，8080 gateway HTTP。前端公开入口只有127.0.0.1:5173，/api代理8080；5180是前端内部开发端口。旧identity8081仅遗留独立测试，不作为本批浏览器入口。Ctrl-C只关闭自己的终端，不停止其他顾问进程。

可用-Batch5Slice1或-Batch5Files读取项目外固定第一/文件切片JAR，用于历史证据重放；日常最新交付不加开关。最终新源码/制品与这些历史固定JAR的差异和验证结果见第五批后端证据，不能把旧制品Chrome结果当作新制品全部验收。

## 已有资源与权限

既有独立库增量由Migrate-Workflow管理，目前identity V10、ticket V5（取消）；asset沿已有迁移。显式Prepare-TicketChain/Migrate-TicketChain只用于获授权的资源/迁移操作，前端无需重跑；不要用旧Prepare-AssetChain覆盖第五批分边配置。既有密码未重置；3名租户管理员按非秘密清单显式ticket.read，版本与审计同事务，不按角色名/撤权能力推导资产或工单权限。

人类账号当前密码引用沿前端最新记录的项目外受限frontend-browser-passwords.json及真实样本ref；不可再用已改密账号的初始化旧值。JUnit随机CH_租户/账号仅夹具，不是页面必须前置。真实客户通过已验资产开通流程即可选设备报修，无需JUnit/手工SQL。

私密配置secrets/chain每角色仅所需调用边和精确对端信任库；新增gateway-ticket/ticket-identity/ticket-asset/asset-ticket四条独立凭据，TLS不trust-all、不安装系统证书。私有二进制位于C:/ias-local/Develop/IAS-MySQL84/private-files/ticket，ACL当前操作用户/SYSTEM，详情与维护命令见ticket/README。不得将凭据、文件内容、Cookie或授权日志放工作区/Git。

runtime无跨库/DDL/DELETE权限；ticket创建操作和文件元数据必要UPDATE，工单仅批准的状态/版本/当前指针等列可UPDATE；引用/取消历史/下载授权/审计为追加用途，不授历史改写。文件专用流式路由，有界32KiB、并发4、上传30/分钟/读取300/分钟、传输120秒；普通JSON32768字节/控制响应1MiB保持。控制核验10秒总/单次2秒，READY前新鲜复验；新Range逐次当前鉴权，应用授权300秒不能代替Cookie。

## 验证和协调

环境工具/Verify-AssetChain.ps1目前构建整个reactor并运行全部测试，含新增ticket套件。执行前声明3307/8080/8081/8441/8442/8443及受限回执代理临时loopback端口窗口独占，结束明确释放。identity旧回归物理启停3307，gateway新回归会停止自己持有的identity/asset/ticket进程及精确TLS回执代理，不能与前端真实联调同时运行。

测试分类及实际计数以协作记录/阶段0/identity第五批运行证据/验证摘要.json为准。API四进程/真实TLS、受控领域入口/审计故障、24小时时钟、前端真实Chrome与合成回调及独立验收分别记录。不将-DskipTests打包、字段静态审查或切片通过称作完整第五批联合通过。

维护环境工具/Maintain-Ticket.ps1默认dry-run，实际执行必须明确-Ids UUID数组及-Execute；-TestNow只用于这些新样本受控时钟。COMMITTED只确认，ABORTED才释放；过期上传先代际栅栏转FAILED供同键重传，未引用READY/FAILED满24小时才可删除，当前租约/未知创建/历史引用保护不因时间跳跃绕过。孤儿磁盘安全ID报告待核对，不猜测删除。

本批未推送、部署、安装新服务或操作3306/旧VM，不清理重复JDK。生产TLS轮换/容量/杀毒CDR/灾难恢复及未来外部取消/转派/重开不在此次结论内。

内部对端地址默认固定8441/8442/8443；只可在项目外受限peers私有配置设置baseUrl，校验HTTPS、127.0.0.1、纯端口地址，仍严格使用具体对端证书。故障回归使用临时私有ticket配置将asset控制边经过动态TLS回执代理，真实服务不换端口；结束删除临时配置并恢复默认JAR进程。不接受浏览器后端地址参数。


第九批取消使用同ticket锁和原UUID：未受理直接取消、受理后独立待审申请、显式ticket.cancel.review及当前企业scope审核；待审不暂停工程师，拒绝不回退主状态，客户CONFIRM使当前申请同事务不再适用。只做普通无外部路径；启用/未知配置或不支持引用拒绝取消终结。不删历史/已引用文件，不伪造回复/客户确认；取消时间与双预算冻结区别于解决finalFrozen。

旧私有轮换备份清理仍单独OPEN；不得重试删除、读旧值或用于启动，现行Start-LocalChainService只用当前chain配置。Verify-Batch9Staff/Verify-Batch9Demo分别为只增显式客服审核能力与真实账号API验证；已有计划/证据拒绝自动重跑，结果未知保留原键参数。运行和测试证据见identity第九批运行证据，不等同Chrome或独立验收。

### 第十批转派 B10-1
现行契约第20节；identity V11只扩展显式ticket.transfer.request能力枚举，不自动授权；ticket V6保存请求、唯一终态、追加协调事实和当前指针。运行账号三张新历史表仅INSERT，ticket仅增加当前指针列UPDATE，不修改历史assignment。
客服当前read/transfer.request及快照企业、目标部门范围发起/撤回；指定目标仅有限收件。目标当前read/accept资格可拒绝，接受额外要求record及原指定启用部门。接受前原人继续负责，接受同事务创建已接单新assignment并切换当前对象权限，历史分派/旧grant不保留读权，独立当前客服scope另判。
拒绝/撤回/24小时超时保留主状态且持久协调；分钟/启动有界SYSTEM扫描，GET只计算有效状态不落库。补充请求、方案提交、取消申请同ticket事务终止当前转派；不更换round、不重置首响/双72h预算、不重复计工时。旧成功UUID按当前授权回放；新UUID操作历史request不得终结另一笔当前请求，即使它已超时。
Verify-Batch10Staff.ps1使用现有离线初始化/配置工具，仅新建第二工程师、客服受控CHANGE只增transfer.request；计划/证据已存在拒绝盲目重跑。Verify-Batch10Demo.mjs对新目标完成首次改密，其余账号密码不动；默认四JAR/真实HTTP与前端Chrome、独立验收分层记录。只读取项目外现行秘密ref，不读取旧轮换备份。
测试与交付入口：协作记录/阶段0/identity第十批运行证据/验证摘要.json。以实际逐轮结果为准，不把构建或部分回归称作联合验收；旧私有备份清理继续独立OPEN，不重试删除。
### 第十一批重开 B11-1
原契约21与任务13。原报修人当前成员在真实关闭后168小时内（等于允许）申请，主管明确ticket.reopen.review/read、企业及独立源关闭部门范围审核；窗口内有效申请可窗后审批。批准才创建新round与批准时SLA快照；指定候选也只ASSIGNED，须本人普通接单。原submitted_at保持原报修时间，当前轮round_started_at独立计时。
新增identity V12审批部门范围/明确能力，ticket V7持久轮次/重开请求/唯一决定，V8迁移本批早期冻结值为ticket_round_freeze唯一追加事实；运行账号对轮次元数据、冻结、请求/决定仅INSERT，无历史UPDATE/DELETE。旧首轮按原ID/实际起点和参数回填LEGACY_BACKFILL，不倒编发布。轮次起点、规则、工时/attemptNo/两份72h及各轮确认冻结分开；同轮退回/转派不重置。
人工round-response入口供无负责人客服接手；公开记录/补充/解决共享首响CAS及ROUND_FIRST_RESPONSE审计，审批/分派/内部接单/内部备注/客户回复不计。所有当前写核round/版本/assignment，历史接口按可选roundId过滤且先核当前整单权；旧负责人不凭历史、旧成功UUID或旧grant继承权限。GET不补写或释放，新候选失格的有界启动/分钟SYSTEM扫描仅撤ASSIGNED，不释放已接单IN_PROGRESS，并沿ID-07终止可能的待转派。
Initialize-Batch11Staff.ps1沿既有离线工具建立最小主管（无ticket.assign）并只给已有客服加ticket.respond；原计划/证据存在拒绝盲目重跑。Verify-Batch11Demo.mjs使用现行受限秘密ref，只有新主管首次自助改密，实际账号HTTP验证三轮/再关闭/私有引用；不代替前端Chrome或独立验收。IAS_TICKET_SLA_RULE仅项目外受控规则配置，默认SLA_V1，格式包含version、responseMinutes与resolutionHours的P1/P2/P3正整数；新轮冻结当时值，已有轮不追随改变，不宣称规则管理后台。
运行证据入口：协作记录/阶段0/identity第十一批运行证据/验证摘要.json；测试、受控领域时钟/审计CHECK/规则配置与真实账号HTTP/页面分层。仅3307现有独立实例/当前chain，不占8081，不改前端、不推送部署；旧私有备份清理独立OPEN，不读旧值/重试删除。