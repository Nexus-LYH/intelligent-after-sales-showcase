# 资金 authority 事实契约 v1（2026-10-24，候选）

本契约是 finance 自有校验边界，不是新公共路由或已完成的资金真实业务验收。公共 W/P/S 路由与可信边由技术负责人冻结；未确认的事实端口不猜路径。现有 money API、权限和 Q/C/H/B、永久财务审批标记不变，不增加费用结清关闭门槛。

## 1. 获取与绑定

`MoneyAuthorityFacts.Provider` 仅用于服务端取得事实，不接收浏览器传入的“已批准/已支付”断言。生产默认 provider 不可用，保持各动作原有 503；构造器注入仅为后续权威适配和明确测试事实提供入口。当前身份、显式 finance.money.* 能力、当前企业 scope 仍由原在线授权检查，事实不能替代权限。

每次获取携带 Binding：tenant/actor、企业/费用组、原 operation UUID、动作、expected account/group version、完整规范命令的 authorityDigest。该摘要与数据库内部 request hash 分开命名，不能混用。摘要包含金额、原收款、凭证文件/摘要、覆盖、原因等全部命令字段。所有返回事实必须回显完全相同的 Binding，包含非空 receiptId 和 checkedAt；取证时间落在本地 now-2s 至 now+250ms 范围。过期、错配、重复事实类型、缺事实或格式异常均 503，不沿用旧缓存放行。

provider 必须先验证实际调用的可信身份与原事实，不跨服务读表。新鲜回执可以证明不可变历史事实，但不能把历史通过扩大为当前执行权限。校验器只能验证已取得证据的结构和语义，不能自行认证证据来源。实际适配须在完成来源认证、完整绑定及必要当前版本核验后才启用。

## 2. 分动作权威事实

- 应收：F 原不可变收费报价/客户确认；P 已完成审批、审批所指原 quoteId/quoteVersion、完整覆盖摘要及主管决定 receipt，冻结 basis 的永久财务标记要求财务决定 receipt；实际执行 owner 的逐行执行 receipt。仅对应已执行且客户授权行入账，行集合相同，金额为正且不超过原报价。当前组版本与被批准报价版本不同，不能要求两者相等或取最新报价替换原报价。
- 应收降低：原 bill/line、新金额、调整审批及覆盖摘要、客户确认和财务决定，状态 COMPLETED；原应收关系和严格降低由本域账本再次校验。不通过改报价或成本负数暗改应收。
- 收款：真实收款 verifier 返回 RECEIVED/COMPLETED，精确业务 paymentId、金额、外部流水、日期、方式和凭证摘要；私有文件 owner 返回该业务对象的 FINANCE_COLLECTION/AVAILABLE 事实。不能以凭证文件存在替代真实资金证明。
- 退款：真实退款 verifier 返回 REFUNDED/COMPLETED，绑定 refundId、原 paymentId、金额、完整凭证；私有文件 purpose=FINANCE_REFUND。承诺、待审核或支付未知都不能记为实际退款；本域再核原收款余额、该账单多收上限与幂等。
- 错录冲正：原 paymentId、correctionEvidenceId、VERIFIED_ENTRY_ERROR 和经核验 actualUnreturnedCents。不得把真实收到而尚未退回的资金以普通冲正抹掉。当前独立冲正仅支持核验实际未退资金为零；非零返回 409 CORRECTION_REQUIRES_ATOMIC_REBOOKING，待原子重记账/保留资金设计冻结后再扩展，不声称该资金纠错路径已完成。

Assessment 是服务端组合证据：F 拥有报价与确认，P 拥有审批决定，执行 owner 拥有执行结果。任何一个 receiptId 都不是跨 owner 批准的替代物；覆盖摘要的权威生成与每行执行金额归属须由对应 owner 确认。资金核验、私有文件 provenance 的具体 owner/端口和最小授权边尚未冻结，当前没有可用生产正向 provider。

## 3. 错误、恢复与事务

缺端口保留既有各动作 *_PENDING 503；已取得但不完整/错配/过期事实分别 FINANCE_AUTHORITY_FACT_MISSING/MISMATCH/STALE。未知结果仍查原 operation，原 UUID 和完整参数恢复，不能换键重记资金。不同参数冲突、当前授权重核、账本与 operation/audit/outbox 同物理事务沿原实现，不新增外部成功等于本域已提交的推断。

## 4. 待 owner 冻结及验证层

技术负责人需确认：P 完成审批及覆盖事实的精确可信端口/绑定；各执行 owner 的授权执行覆盖；实际资金 verifier 与财务私有文件 owner；非零真实资金错录的原子纠正协议。此处是明确缺口，未自动新增 API、表、能力或服务。可独立交付为严格类型事实、失败关闭校验及组件测试；真实可信 HTTP/TLS、真实审批/执行/资金和故障恢复需取得冻结端口后另验。既有 MySQL 账本测试不冒充这些事实的生产联调。

续作证据：20261024资金事务的MoneyAuthorityTransactionTest直接把本校验器接入JdbcMoneyLedger，3项H2事务测试覆盖默认不可用/过期/缺永久财务决定拒绝无写入、原键成功回放仍重核权限及新鲜事实、审计失败全回滚与原UUID恢复。全套finance98项中95执行通过、3实际MySQL条件跳过，未操作3307。报价/确认的公共W持久pull运行结论由技术负责人10:07:48记录独立引用，不替代本资金authority生产事实接入。

### 4.1 V8人工资金核验归属（2026-10-26）

首期实际资金核验owner为finance本域的受控人工动作：必须先在线重核员工启用状态、细分finance.money能力和当前企业scope，再绑定原operationId及完整 authorityDigest。原P completion与其原报价、客户确认、永久财务审批分支继续由可信只读事实核验；LABOR/OTHER收费执行receipt必须已在原应收入账authority中形成不可变覆盖，资金动作只引用该原bill/completion，不能用最新报价或浏览器布尔替换。私有凭证owner仍由ticket精确返回AVAILABLE/sha256/业务对象归属，但文件存在不构成到账或退款。

owner事实404、超时和5xx一律转为503 FINANCE_AUTHORITY_UNAVAILABLE，不得按“未找到即无须核验”放行。V8终态表持久化的 authority_digest 必须等于 MoneyAuthorityFacts.binding(actor, owner, operation, command)，供审计和通知关联，不能替代外部owner回执本身。

## 5. W LABOR/OTHER执行事实最小候选（2026-10-24，待root冻结，未实现peer）

本节是给W单写主责冻结的精确候选，不表示端口、V16、执行登记或资金业务已经运行。root当前持3307窗口，本角色未执行SQL/HTTP/制品替换。原第1～4节为先前候选证据，不能据其旧“全部生产不可用”描述覆盖后来53文件中已经实现的人工资金/V7 mirror；当前新实现及验证层见finance-highwater-production/验证摘要.json。

### 5.1 先有W持久执行事实，后有纯查询

目前ticket_processing_record的RECORDS只有原recordId、ticket/round/assignment/actor/operation、正文与工时，不存在P completion/原报价行/费用代际/金额关系。存在一条记录、工时大于零、方案已解决或F旧PlanLine.executed布尔，均不能推定某收费行已执行。

建议W以显式执行登记建立不可变事实：原tenant/company/group、P completion/application、quote/version/digest、generation、coverageDigest、lineId/kind/原授权金额、实际执行金额、原processingRecordId/ticket/round/assignment、当前真实执行actor、原登记UUID、登记回执UUID与时间。登记时在W原ticket/round/assignment及费用代际同锁边界核当前工程师资格、ticket.record、当前公司/部门/原负责人关系、批准完成与客户确认，原UUID操作/审计/不可变执行事实同事务；不能由F直接写W表或把浏览器executed=true签成服务事实。

本最小slice仅LABOR/OTHER。同一个processingRecordId+lineId只能登记一次，同一报价lineId只生成一条固定金额执行事实，正金额不超过该原批准行金额；缺原记录或上下文不符拒绝，不从工时计算未经批准的计价。新登记必须当前合法generation OPEN且未被报价栅栏阻断。PART缺精确S USE/实际数量/批准行映射继续503，不用W处理记录代领用。

### 5.2 拟冻结查询端口及精确请求

可信调用边finance-ticket，POST /internal/ticket/fee-execution-fact；只读，不创建执行事实，不修改工单/工时/代际。精确9键：

- sessionToken：当前资金执行人的会话，只有可信调用请求可携带，不记录日志。
- groupId、completionId、quoteId：canonical UUID，来自F当前money命令所指P不可变completion及其原F报价；不是最新报价猜测。
- quoteVersion、generation：规范正十进制字符串，分别原F报价版本、原P费用代际，不能用currentGroupVersion互相替换。
- operationId：原Assess UUID，canonical UUID；未知结果只用此原UUID/原完整参数恢复。
- authorityDigest：MoneyAuthorityFacts.binding(actor,owner,operation,command)原authorityDigest，64位小写hex；不能换用finance_operation.request_hash或coverageDigest。
- lines：1～50项数组，每项精确lineId/amountCents两个字符串键；lineId不重复，按UUID字典序排序。集合与原Assess.approval.executedAuthorizedLines精确相同，金额为规范正整数分。

tenant/actor由W在线identity取得；company由W本域不可变原ticket/company及原P completion核定，F再次与自己当前principal及费用组owner匹配。禁止信任请求中的X-User/Tenant/Role等自填头，禁止跨域直接读F/P表。

### 5.3 精确响应候选

顶层精确字段：tenantId、actorUserId、companyId、groupId、completionId、quoteId、quoteVersion、generation、coverageDigest、operationId、authorityDigest、receiptId、state、items、checkedAt。除items数组外均字符串，state只允许EXECUTED；checkedAt为服务端实时UTC ISO时间，并使用现有Chain新鲜度/请求deadline验证。

items与请求lines精确同集合并按lineId字典序，每项精确lineId、ticketId、roundId、kind、amountCents、processingRecordId、executionReceiptId。所有值为字符串，kind仅LABOR/OTHER；receipt/record/line/ticket/round均canonical UUID。amountCents等于该不可变W执行事实金额，本最小slice要求本次请求金额与它精确相同，不允许仅凭原报价上限自填另一个金额。receiptId取items第一项已持久executionReceiptId，必须是实际W登记事实回执，不用查询临时UUID或浏览器上传ID冒充。

coverageDigest为P原完整quote lines的5字符串键规范摘要：id/ticketId/roundId/amountCents/kind，按id排序，每行TreeMap，经Chain.JSON紧凑UTF8序列化SHA256小写hex；不是只对本次选中lines计算。W响应每行还须对应P原quote同一line/ticket/round/kind，执行发生时的授权金额与原quote一致，实际执行金额<=原批准金额。

### 5.4 F组合与权限

F先在线MONEY_WRITE和CSRF，再核finance.money.read+finance.money.assess、员工启用部门与当前企业scope，随后按原completionId查询P四键原完成事实；P原主管/必需财务receipt、quote/version/digest、coverage/永久分支不可缺。F加载本域原收费quote及原客户确认，不能用当前组版本替原quoteVersion，不能用P返回的布尔伪造客户确认。W查询在线MONEY_READ并另外检查finance.money.assess、员工启用部门、当前企业scope；不因此授ticket正文或工程师执行权限。

F组装MoneyAuthorityFacts.Assessment：header.binding为原完整money binding；header.receiptId取真实W receiptId，checkedAt沿W原checkedAt；quote为本域原QuoteVersion；customerConfirmation为本域原不可变确认operationId；completedApprovalId=P completionId；approvedQuoteId/version为P所指原quote；coverageDigest=P原完整覆盖摘要；decisionReceipts精确SUPERVISOR与必要FINANCE原receipt；executedAuthorizedCents及executionReceipts由W items逐项映射，集合、原金额、原quote的ticket/round/kind精确匹配。任一缺失/错配/过期都不写应收；本域账再核expected account/group version与行不重复累计。

原执行事实不可因换负责人或新报价而被删除或改写。新执行登记必须当前代际；查询历史实际执行事实只是读取既成事实，不授旧代新执行。是否允许尚未入账的旧代已执行行随原授权首次入账，由root结合当前quote不可替换已执行行规则固定；F不能自行把历史读取等同新收费授权或自动取新报价替代。

当前合法但尚无执行事实不签EXECUTED；对象不可见404、当前身份/能力失败401/403、代际或绑定冲突409、owner不可用/不完整/超时503，均无应收写入。响应原请求UUID/摘要错配必须拒绝；未知资金结果仍查原F operation，不能重新登记W事实或更换资金UUID。查询成功不代表应收事务已提交、实际收到钱或outbox已送达。
