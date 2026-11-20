# ticket 本地报修及私有文件

本模块已沿第五至九批增量推进，当前字段/状态/错误见开发者设计/登录与会话最小契约草案.md第15～19节及任务拆分11。报修先到待受理，已有受理/分派/处理/解决确认/补充；取消是独立终结，不表示解决。staff需当前明确能力与企业scope或本人分派范围。

Java21/Boot4.0.8/MVC/MyBatis依赖线，独立TLS8443。仅ias_ticket_test@3307，迁移/runtime分离；不跨库JOIN/写入。gateway无数据库，ticket自己在线核验identity并按asset当前成员事实授权；工单公司/设备为不可变历史快照。四个服务启动见services/README及环境工具/Start-LocalChainService.ps1 -Service ticket。

创建先登记稳定操作/候选工单/绑定及不可变摘要，短执行租约/CAS推进。asset reserve裁决设备归属版本和准入，本地工单/原始报修/文件引用/COMMITTED/审计同事务，再确认绑定；未确认202 COMMITTED，GET纯查询，原UUID显式resume。已COMMITTED只确认；闲置未提交先ABORT栅栏后凭事实release，保留ticket幂等终态及asset释放墓碑，不依赖浏览器存活，不按超时直接释放。公网转移关闭，测试只调用受控已批准领域入口。

私有二进制固定C:/ias-local/Develop/IAS-MySQL84/private-files/ticket，ACL仅当前操作用户/SYSTEM。数据库不保存客户端路径；文件名仅展示。上传元数据4个文本part在前/file最后，真实JPEG/PNG/PDF校验（ImageIO/PDFBox3.0.6，不是杀毒/CDR）；图片最多1600万像素、PDF不接受加密或超过1000页，以限定解析资源。文件20MiB上限、有界32KiB流与随机存储ID/执行代际；READY前当前权限重新核验、二进制force/原子改名＋元数据事务，失败不伪READY。普通JSON限制不扩大。应用授权最长300秒，Cookie/attempt/file绑定；每次新GET/HEAD/Range都重新在线及对象鉴权。上传前/完成前为10秒控制阶段，文件传输为120秒，网关关闭看门狗避免输入流无限阻塞。新请求撤权立即拒绝，已传内容不承诺追回。

离线维护：从工作根目录运行环境工具/Maintain-Ticket.ps1，默认dry-run。-Ids UUID数组限定新操作/文件，-Execute才执行；没有明确ID禁止执行。-TestNow仅配合明确ID用于受控24小时边界，不作生产时钟。系统不借用户秘密创建新工单，只确认COMMITTED/释放ABORTED；文件按READY/失败最后更新时间至少24小时、当前执行租约、未知操作及引用锁复查。引用历史不能删，未决先对账，DELETING可重试。未知孤儿磁盘只报告安全ID，不猜测后删除。维护不自动启动、不提供HTTP后门。

测试位于gateway/TicketFlowTest：实际四进程/精确信任TLS/三独立库，受控故障和时钟另标；受控领域测试经生产方法验证设备锁及版本，不开放审批API。运行独占窗口包含3307/8080/8441/8442/8443；前四批回归会实际启停DB，必须先协调。独立验收/前端Chrome证据不由本模块自测代替。

PDFBox文件读取/磁盘缓存用法依据[官方3.0迁移文档](https://pdfbox.apache.org/3.0/migration.html)，实际版本/解析与20MiB流式边界以本项目运行证据为准。上传租约过期先封禁旧代际、转FAILED供同上传键/同fileId显式重传，不等24小时才恢复；私有二进制落盘失败仍FAILED，不误报READY。


### 第七批普通维修收尾
同版线协议在项目根开发者设计/登录与会话最小契约草案.md第17节；解决方案/客户唯一决定/客服关闭为不可变历史，当前方案通过ticket行指针及同一事务版本互斥。解决后当前assignment工程师仍本人只读，记录/再解决仅IN_PROGRESS；仅原报修人当前有效成员能决定。新增显式ticket.solution.submit和ticket.close，不依角色名自动授予。
- 环境工具/Migrate-Workflow.ps1：既有3307守卫，identity V8能力白名单及ticket V3收尾历史/当前指针，运行账号仅新增current_solution_id列UPDATE和三历史表INSERT（原SELECT沿库授权），不授历史UPDATE/DELETE。
- 当前四服务沿环境工具/Start-LocalChainService.ps1 -Service identity|asset|ticket|gateway运行默认target JAR，不加历史切片参数。asset须在线才能用环境工具/Configure-Staff.ps1 -Plan <明确计划> 默认dry-run；-Execute提交；同UUID同计划回放，CHANGE使用实际当前版本，不重置密码。已准备bootstrap/batch7-staff-capabilities.json仅为IAS6_A的service.browser/engineer.browser保留原事实各增一个能力；实际执行与版本在后端记录/第七批证据，勿自行INITIALIZE恢复授权。
- 环境工具/Verify-Batch7Demo.mjs：使用项目外受限frontend-browser-passwords.json的三个当前browser ref，仅真实API演练普通关闭和两次退回关闭，无JUnit或手改业务库，不改密。每次POST前保存原UUID/完整非秘密参数；已有证据文件则拒绝自动再跑。结果未知必须先检查原操作并显式原键原参恢复，不删证据/换键掩盖；它是有界验证脚本，不是完整认证或恢复平台。
- IAS_TICKET_EXTERNAL_WORKFLOWS默认DISABLED。相关流程仍无业务入口，核验只声明ORDINARY_REPAIR_ONLY/NOT_ENABLED，绝无审批/库存/费用已清零证明。非支持scope、相关引用或启用/未知配置拒绝解决/关闭。后续所有者启用前须建立真实核验与同门闩/可恢复屏障；不将未结算或泛化费用争议作为门槛。
- CompletionTiming为显式时间输入的纯计算；同轮待确认不重叠区间累计最多扣72h，完成时间不停止计时，CONFIRM时冻结最终指标，关闭不增长。真实72h不等待，以单元时间输入和隔离历史时间夹具验证；后者不是实际72h运行证明。客服coordination筛选纯读，不投递提醒或自动关闭。
- 后端自验、前端真实浏览器、技术审查和独立验收分层，以协作记录/阶段0/identity第七批运行证据/验证摘要.json（交付时写入）及角色记录为准，不据本文声明通过；本批不开放转派/补充/重开/评价/新处理附件。

### 第八批补充资料与回复文件
现行同版契约为开发者设计/登录与会话最小契约草案.md第18节（B8-1）；第七批上节的范围说明是历史。本批增加补充请求/唯一回复/恢复原状态及客户专用新附件，不开放取消/转派/重开、企业管理员代回复或工程师通用上传。
- Migrate-Workflow.ps1在既有3307增量identity V9/ticket V4；仅当前补充指针/首响等级列UPDATE及三补充历史表INSERT。ticket_file的UPDATE收窄为state/generation/lease_until/size_bytes/media_type/updated_at；不能改原actor、ticket/request/reply键等上下文。该脚本可重复执行，运行时不做DDL。
- Supplements处理请求/回复、不可变公开历史、原UUID结果与事务审计；WAITING_INFORMATION保存服务端resumeState，只有当前原报修人回复才能恢复。首响CAS与等级冻结同事务；补充与确认各一份72h累计扣除，CONFIRM持久冻结两项。没有自动推进/假通知。
- SupplementFiles限定客户multipart上下文，FileStore复用流式/租约/代际/私有Range，补充文件没有submission_id/device_id/ownership_version，不经新报修准入。ticket_file_reference保护所有实际引用，补充引用另关联唯一reply；原报修attachments列表不混入后续补充文件。旧请求完成后只允许当前读权查询历史，不允许新上传或迟到最终化。
- SupplementFlowTest真实默认四JAR/3307新样本；SupplementTimingTest显式时钟；SupplementGrantTest实际权限零行探针。故障、历史时间夹具、实际60s、工具账号HTTP、前端浏览器及独立验收分层，以第八批运行证据及角色记录为准。
- Verify-Batch8Demo.mjs只读三个现行browser秘密ref，经真实API执行受理前文字、处理中两次新PNG回复、同round/assignment/首响及确认关闭；已有证据拒绝自动重跑，保留原POST键/完整非秘密参数。运行前必须执行明确受控能力CHANGE并独占窗口，不据脚本存在声明通过。

### 第九批普通取消
现行B9-1契约19；Migrate-Workflow在3307增量identity V10/ticket V5。Cancellations以同ticket短事务保存申请、唯一决定和取消终结三类追加历史，运行账号仅INSERT及current_cancel_request_id列UPDATE，无历史UPDATE/DELETE。原assignment、方案、首响、工时及引用均留存。ticket_cancellation中的terminated_supplement_id/terminated_solution_id是不可变终止事实，不插假回复/确认决定。
取消审批显式ticket.cancel.review＋ticket.read＋当前企业scope；客户独立TICKET_CUSTOMER_CANCEL用途，只原报修人当前ACTIVE快照企业成员。未知/启用外部流程或related引用不能终结。待审保持主流程；CONFIRM与申请NLA同事务。取消先提交持久废弃未引用UPLOADING代际，流结束不能READY；已READY孤稿仍走24h维护，已引用附件当前合法身份保留私有Range。
终结timing快照clockStopped/terminalReason=CANCELLED/terminatedAt/terminatedEffectiveResolutionMs与独立双72h预算冻结，finalFrozen=false且无最终解决达标结论。CancellationFlowTest/GrantTest为真实默认四JAR/3307及运行权限探针，故障/时钟/60s分别标注。前端Chrome和独立验收交其他顾问；真实脚本结果不代替它们。

### 第十批转派 B10-1
现行契约第20节；identity V11只扩展显式ticket.transfer.request能力枚举，不自动授权；ticket V6保存请求、唯一终态、追加协调事实和当前指针。运行账号三张新历史表仅INSERT，ticket仅增加当前指针列UPDATE，不修改历史assignment。
客服当前read/transfer.request及快照企业、目标部门范围发起/撤回；指定目标仅有限收件。目标当前read/accept资格可拒绝，接受额外要求record及原指定启用部门。接受前原人继续负责，接受同事务创建已接单新assignment并切换当前对象权限，历史分派/旧grant不保留读权，独立当前客服scope另判。
拒绝/撤回/24小时超时保留主状态且持久协调；分钟/启动有界SYSTEM扫描，GET只计算有效状态不落库。补充请求、方案提交、取消申请同ticket事务终止当前转派；不更换round、不重置首响/双72h预算、不重复计工时。旧成功UUID按当前授权回放；新UUID操作历史request不得终结另一笔当前请求，即使它已超时。
Verify-Batch10Staff.ps1使用现有离线初始化/配置工具，仅新建第二工程师、客服受控CHANGE只增transfer.request；计划/证据已存在拒绝盲目重跑。Verify-Batch10Demo.mjs对新目标完成首次改密，其余账号密码不动；默认四JAR/真实HTTP与前端Chrome、独立验收分层记录。只读取项目外现行秘密ref，不读取旧轮换备份。
测试与交付入口：协作记录/阶段0/identity第十批运行证据/验证摘要.json。以实际逐轮结果为准，不把构建或部分回归称作联合验收；旧私有备份清理继续独立OPEN，不重试删除。
ID-07：release-invalid-assignment在同actor/ticket/source assignment事务锁内调用Transfers.terminate，以SOURCE_ASSIGNMENT_RELEASED追加NLA/清当前转派指针/审计，然后释放源分派。历史不覆盖；新分派可即时新转派，成功原UUID仅回放历史，不影响新指针。TransferReleaseTest覆盖详情再分派闭环、旧请求与原键、接受/扫描两种先后和实际竞争、NLA及释放最终审计故障全回滚与原键恢复。修复证据见协作记录/阶段0/identity第十批运行证据/ID-07修复；须由技术/测试复核及新制品真实页面补验。

### 第十一批重开 B11-1
原契约21与任务13。原报修人当前成员在真实关闭后168小时内（等于允许）申请，主管明确ticket.reopen.review/read、企业及独立源关闭部门范围审核；窗口内有效申请可窗后审批。批准才创建新round与批准时SLA快照；指定候选也只ASSIGNED，须本人普通接单。原submitted_at保持原报修时间，当前轮round_started_at独立计时。
新增identity V12审批部门范围/明确能力，ticket V7持久轮次/重开请求/唯一决定，V8迁移本批早期冻结值为ticket_round_freeze唯一追加事实；运行账号对轮次元数据、冻结、请求/决定仅INSERT，无历史UPDATE/DELETE。旧首轮按原ID/实际起点和参数回填LEGACY_BACKFILL，不倒编发布。轮次起点、规则、工时/attemptNo/两份72h及各轮确认冻结分开；同轮退回/转派不重置。
人工round-response入口供无负责人客服接手；公开记录/补充/解决共享首响CAS及ROUND_FIRST_RESPONSE审计，审批/分派/内部接单/内部备注/客户回复不计。所有当前写核round/版本/assignment，历史接口按可选roundId过滤且先核当前整单权；旧负责人不凭历史、旧成功UUID或旧grant继承权限。GET不补写或释放，新候选失格的有界启动/分钟SYSTEM扫描仅撤ASSIGNED，不释放已接单IN_PROGRESS，并沿ID-07终止可能的待转派。
Initialize-Batch11Staff.ps1沿既有离线工具建立最小主管（无ticket.assign）并只给已有客服加ticket.respond；原计划/证据存在拒绝盲目重跑。Verify-Batch11Demo.mjs使用现行受限秘密ref，只有新主管首次自助改密，实际账号HTTP验证三轮/再关闭/私有引用；不代替前端Chrome或独立验收。IAS_TICKET_SLA_RULE仅项目外受控规则配置，默认SLA_V1，格式包含version、responseMinutes与resolutionHours的P1/P2/P3正整数；新轮冻结当时值，已有轮不追随改变，不宣称规则管理后台。
运行证据入口：协作记录/阶段0/identity第十一批运行证据/验证摘要.json；测试、受控领域时钟/审计CHECK/规则配置与真实账号HTTP/页面分层。仅3307现有独立实例/当前chain，不占8081，不改前端、不推送部署；旧私有备份清理独立OPEN，不读旧值/重试删除。
### 审批关联集成前置（2026-10-21，BE-W）
- 域协议见approval-boundary-v1.json；只提供工单事实及原提交UUID的持久关联意图，不提供审批决定或通知ACK。P必须先登记再提交本地申请/outbox；未知不得删除意图。
- V9增量表运行权限仅SELECT/INSERT，actor及ticket锁串行化后普通读取不可变关联/assignment；不授历史UPDATE。取消终结同ticket锁拒绝已有外部意图；关闭不增加结清门槛，同时真实标注关联存在、外部清理未核验。新JAR启动要求V1/V9已成功迁移。
- 本轮只在本角色隔离快照与新qa_ticket_boundary_* schema验证；未将V9施加于ias_ticket_test，未更换共享target/JAR，未改公共启动脚本/可信配置。BE-0在集成候选中先迁移V9并最小授权，再配置approval-ticket两条精确POST白名单及TLS/身份链；原四服务候选不可混称新端口已可用。
- 当前域协议/组件与真实MySQL锁验证不等于P→N真实HTTP/当前身份/页面通过；approval/notification/stock与公共文件由已登记所有者接入。无自动解除意图的路径，外部取消协调尚未完成。
