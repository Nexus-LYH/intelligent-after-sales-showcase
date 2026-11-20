# approval 备件审批后端切片

当前实现 PRD FR-APPROVAL-01/03 的部分备件内核、JDBC 本地事务仓储及 REST 入口；独立 Java 21 模块，未加入公共父 POM。默认可信资格适配器拒绝所有请求，不是已联调或已部署业务服务。单备件HTTP切片机器契约 [api-parts-v1.json](api-parts-v1.json) 已于2026-10-21 10:04按 `1.0.0-slice.1` 冻结供局部前端开发；这仅冻结本片字段/错误/恢复边界，不表示资格适配/真实MySQL已通过，未实现范围仍保留。

`PartsApproval.submit` 接收单仓库、单备件申请。申请快照绑定租户、申请工程师、工单、assignment、部门、仓库、数量与用途；正整数数量、本人处理中和仓库范围由输入与受信当前资格共同校验。多仓库由调用者拆成不同申请。无合格主管返回 `WAITING_CONFIGURATION`，绝不默认批准；当前主管就绪后可进行决定。

`PartsApproval.decide` 要求当前启用主管、同租户/申请部门/工单范围、不是申请人；拒绝理由必填。一次决定后仅相同 UUID 与完整参数的重放返回原快照，异参冲突，不同操作不能再次决定。重放仍要求当前身份/范围；原成功结果可以在工单后续终结后查询，但不产生新执行授权。新增决定需当前工单允许，expectedVersion 匹配。批准只形成审批资格，不扣减或预留库存。

`PartsApproval.withdraw` 仅允许当前启用且具有工单范围的原申请人撤回审批中/待配置申请，保存独立撤回 UUID、参数和时间；不要求 PRD 未规定的必填撤回理由。已批准/已驳回不能借该动作改写历史，已批准取消应走后续取消记录及执行侧释放。已撤回申请不能再决定；相同原撤回 UUID/参数只返回原结果，身份/范围失效仍拒绝。

`PartsApproval.resubmit` 从已撤回或已驳回快照建立全新申请 ID 和提交 UUID（原06 P03允许两类复制）；允许修订申请内容，但仍绑定同租户/原申请人/原工单，重新检查当前本人处理中 assignment 与仓库资格。新申请保留不可变 `Revision(rootApplicationId, number, previousApplicationId)`，首版从 1 开始；修订号与每条记录从 0 开始的乐观锁版本分开。旧数量、用途、提交时刻、决定或撤回事实均不修改。原新版本 UUID 重放还必须匹配同一前版谱系，不能把另一前版或新操作当恢复。单纯调用领域函数不提供跨请求防分叉保证，仓储必须使用下述持久约束。

`ApprovalController` 提供申请/原结果查询/详情/主管决定/撤回的实际 MVC 入口；所有响应 no-store。POST 的 `Idempotency-Key` 必须单一且与 body.operationId 一致；版本及所有长 ID 在 API 中为字符串。未知/重复 JSON 字段、数字版本到字符串隐式转换、浮点数量均拒绝；请求体包含 chunked 实际最多 64KiB。用途5～500，驳回理由5～500，同意意见≤500。当前单行备件申请只是原P03多明细能力的首个切片，不等同整个P03完成。

`JdbcApprovalStore` 同一连接事务内预约 `(tenant,actor,UUID)` 操作键，锁定对象，写申请/CAS或新修订、原回执、追加审计和不可变 outbox。SQL 候选位于 `src/main/resources/db/migration/V1__parts_approval.sql`，仅单独 approval 库；没有自动初始化/执行迁移。查询操作和详情不写业务、操作、审计或 outbox。提交/回滚不确定连接不恢复 autoCommit、不经池包装器 close 返还；生产 Hikari 优先物理 evict，另可物理 abort。两种失效方法都失败时隔离连接，不冒险复位/返池，可能耗尽有界池并要求运维处理，不能假装故障已恢复。

## 实际验证入口

指定现有 JDK21 后运行 `mvn -f services/approval/pom.xml test`。测试含内核、内存 H2 实际 JDBC 事务、Mock 事务连接故障所有权及 Boot MockMvc；不访问共享 MySQL/服务、不占运行窗口。H2测试专用schema把JSON列替换为CLOB及提供UTC_TIMESTAMP测试别名，验证本地事务逻辑，不证明MySQL迁移语法/JSON/锁与最小权限通过。

## 下一切片必须补齐的适配边界

- 当前 Authority records 仅为受信适配层输入类型，不能从浏览器 JSON 直接反序列化为授权事实。BE-0在公共trust/gateway接 `gateway-approval` 及源头/CSRF校验，BE-I/W/S提供当前部门/主管/对象资格、ticket assignment/处理中事实和stock仓库/备件归属；本模块 `ApprovalAccess` 是适配入口。生产默认 `Unavailable` 全503，在此之前不得用测试Principal开后门。跨服务资格验证和工单取消/释放屏障须采用冻结协议；一次远程检查后无锁落库不能声称安全。
- SQL候选与仓储已包含 `(tenant,actor,operationId)` 唯一键、参数摘要、原回执、锁定申请及当前版本 CAS。新版本锁前版并以 `(tenant, previousApplicationId)` 和 `(tenant,root,revisionNo)` 唯一约束防分叉。状态、原UUID回执、追加审计、谱系与outbox同事务；审计/outbox插入失败H2已测完整回滚。仍须真实MySQL迁移/权限/并发及服务边界验证，不将H2逻辑证据称作真实MySQL通过。
- `existingByOperation` 由受信仓储按提交 UUID 定位，禁止把不同操作的任意申请当恢复目标。拒绝/版本冲突由接口映射稳定错误码；不得将参数冲突转成自动新 UUID 重试。
- 本模块 `approval_outbox` 与 BE-N 当前 `event_outbox/event_delivery` 不是已经接通的相同队列。下一切片由BE-0引公共依赖后在本approval事务append通用信封，或提供同库claimer适配；禁止另开notification库事务冒充原子写。未实现真实投递/重试/死信/ACK/重放，不因表里有事件算通知通过。
- 已批准取消/执行侧释放、过期扫描、查询分页、草稿、多明细、规则发布、可信REST/TLS及真实MySQL最小列授权和浏览器均未完成。后续追加实现，不将当前切片等同 FR-APPROVAL-01～03 完整通过。父reactor只由BE-0接，不抢公共文件。
- 费用审批需等 finance 费用组 Q/C/H/B 与永久财务标记协议，approval 负责单一规则发布源；本切片没有费用/金额路由，未引入替代金额门槛。

源码所有权：`services/approval/**` 归 WP-P/BE-P；父 POM、gateway、ticket、identity 仍由原所有者接入，不并行覆盖。

## 2026-10-21 持久切片后的同事务投递增量

`JdbcApprovalStore.complete` 现在使用 notification 库的 `JdbcDelivery.appendOnOwnerTransaction`，把相同稳定 eventId 的信封及 delivery 记录写入 **approval 自己的当前物理事务**。原 `approval_outbox` 保留为追加历史，`V2__producer_delivery.sql` 新建生产者本地投递表。没有调用远端通知库去补写业务事务。当前只订阅 `notification` 信号投影；库存/费用/工单消费者的接入、取消/收费屏障及收件人解析尚未实现。

新安装须完整执行V1/V2并验证MySQL权限及UTC会话，运行时不会自动建表。若已有V1-only历史事件，必须先按原eventId受控回填并核对全部信封，再启用生产worker；本切片没有执行历史回填，也未在3307执行迁移。不得把空的新投递表当历史消息已处理。

审批成功提交的POST原UUID回放重新验证当前提交资格；同一用户仍有读取权限时，可通过GET原操作读取历史回执，但读取权限不能授权POST。独立发现的撤资格反例原红件保留，修复新增回归测试。

实际自验：approval 35项、notification 23项通过。新增两个P→N用例使用两个独立H2 owner数据库：审批业务/回执/审计/历史outbox/投递outbox/dispatch同事务，消费提交后ACK丢失再投只有一个效果；dispatch插入故障使六类事实全回滚，原键可以恢复。采用进程内transport，不是网络/TLS/MySQL/用户通知/生产worker验收。默认可信适配仍全503，不因投递代码接通而开放路由。

2026-10-21 10:02增量自验：既有JDK21独立 `mvn test`，6类共32项，失败/错误/跳过均0。包括H2申请→撤回→新版本→批准及回执不重复、相同操作竞争唯一、审计/outbox故障全回滚、commit/rollback/begin异常不返不确定连接、默认拒绝伪造身份、严格JSON/幂等头/字符串版本/64KiB/浮点数量；Surefire在 `target/surefire-reports`。最初H2 UTC别名签名/访问性失败与Jackson数字版本仍被转换的红XML保留在 `target/evidence/jdbc-first-failure`、`coercion-first-failure`，修正后通过。未使用3307/真实网络/共享服务，未完成可信HTTP或独立/联合验收。

## PN-1接入候选（2026-10-21，覆盖上文“下一切片”的当前状态）

当前本模块已依赖trust/notification，应从父reactor运行 `mvn -f services/pom.xml -pl approval -am test`，不能假定独立模块依赖已安装。P的domain-authorizations、ticket上下文、stock对象、当前审批人及source register适配见TrustedApprovalAccess；仅配置现行私有chain才启用，仍受approval EdgeFilter约束。没有chain继续Unavailable/503。此为源码和端口回归通过，不是实际TLS联调通过。

公共提交departmentId统一assignment现有正BIGINT字符串；code不能替代ID，不能按名称转换。完整命令hash含tenant/actor/原UUID/前版/所有参数。先完成本地转换资格，再source owner持久register，再本地申请/回执/审计/outbox同事务提交。source响应未知时本地回滚，保留源意图等待同UUID同参恢复；不自动删除源意图或发新UUID。真实跨进程提交瞬断尚未验证。

GET当前详情单独投影allowedActions；所有历史操作及写回执默认空动作，不以历史成功授当前权限。动作资格查询不执行业务写入，依赖503继续失败关闭；真正写入仍逐次Origin/CSRF/当前身份/对象复核。源owner收尾、审批→通知网络worker/站内信、历史回填、规则/多明细等尚未因此完成，按原任务14.19分层核对。