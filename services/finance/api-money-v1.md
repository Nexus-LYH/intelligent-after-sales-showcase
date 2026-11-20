# finance 14.25/14.26 候选契约（2026-10-23）

状态：本车道实现候选；不是公共可信链、页面或联合验收结论。公共路由/能力/可信边由BE-0集成，不改其他车道文件。

所有写请求使用原actor、原UUID及完整参数；Idempotency-Key必须等于operationId。金额、版本均十进制字符串，ID为规范小写UUID，CNY分。拒绝额外字段/重复JSON键。错误沿现有400/401/403/404/409/413/503，响应no-store。未知结果先GET原operation，不换键。原成功键重放仍查当前权限，返回原结果而不是当前余额。

## 报价拒绝与撤回

- POST `/api/finance/groups/{group}/quotes/{quote}/reject`：当前企业管理员；正文operationId、expectedVersion（组版本）、quoteVersion、reason（5～500字符）。仅当前客户付费、尚未确认报价。成功CUSTOMER_QUOTE_REJECTED。
- POST同前缀`/withdraw`：当前授权员工/原报价发起人，正文同上，成功QUOTE_WITHDRAWN。不得撤回已获批历史，执行后走取消/补偿。
- GET原quote增加termination=NONE/CUSTOMER_QUOTE_REJECTED/QUOTE_WITHDRAWN；拒绝后customerDecision=REJECTED。确认与终止用同组锁竞争，终止后不能确认或批准。仅移出本报价未执行有效行并按剩余有效行重算Q/B，不恢复被替换旧行；保留历史报价快照、C/H及永久财务标记，不伪造审批驳回或删除已建请求。
- `Access.quoteDecision`必须在本地提交前提供与原操作、组/报价/版本及reason绑定的执行所有者冻结/必要ACK与审批请求终止依据；拒绝需复验当前ADMIN，撤回需复验原发起人资格。**目前生产默认503 FINANCE_QUOTE_FREEZE_AUTHORITY_PENDING**。无内部请求时不创建虚假approval任务。回放只核对原冻结事实与当前授权，不能重启旧代际。

## 资金账

GET `/api/finance/groups/{group}/money`：当前权限下的账本摘要；初始version=0。返回currency、receivableCents、collectedCents（扣除已核实错录冲正后的收款）、refundedCents、settledCents、outstandingCents、overpaymentCents、notice=`人工登记，未接支付渠道`。私有凭证及内部审批依据不对客户输出。

POST `/api/finance/groups/{group}/money/{action}` 共用operationId、expectedVersion（资金账户版本）、expectedGroupVersion（费用组版本），各action必需字段如下：

- assess：billId、completionId、coverageDigest、lines[{lineId,amountCents}]。权威已完成审批及明确客户授权/已执行覆盖，金额≤授权；禁止重复覆盖同一line。不是Q/C/H/B总额或成本差额自动转应收。
- lower：billId、lineId、newCents、adjustmentApprovalId、coverageDigest、reason。只降低该账单已核定行，需要客户确认及财务受控完成审批；原应收事实不删，产生不可变调整流水。
- collect：billId、paymentId、amountCents、voucher、correctionOf（普通登记null，受控错录重记指向已冲正原收款）。同组同外部参考号重记必须显式引用已核实错录且已冲正原记录，同一原记录最多重记一次；保留原参考号唯一归属，不允许跨组挪用。正常收款不能超过该账单及全组当前未收应收；只记录已核实线下真实到账，不调支付接口。
- reverse：originalPaymentId、correctionEvidenceId、reason。只对经核实的错录原有效收款冲正一次，保留原收款。真实收到未退资金不得用此接口抹掉；有真实退款引用的收款不可冲正。
- refund：refundId、originalPaymentId、amountCents、voucher。只核销已核实外部实际退款，金额不超过多收待处理和该原收款可退余额；申请/承诺不生效。

voucher精确字段reference（≤100）、date（ISO日期）、method（≤40）、privateFileId、evidenceDigest（64位小写SHA256）。收款/退款参考号按tenant+kind唯一，跨组不能重复登记；原操作重放不得重复核销。

GET `/api/finance/groups/{group}/money/entries?afterVersion=0&limit=20`按账户版本升序返回不可变流水安全摘要，limit 1～50，无私有凭证/审批证据。

GET `/api/finance/groups/{group}/money/operations/{operation}` 为原actor当前权限下纯查询，不创建账户/账单。成功写返回operationId、action、groupId、groupVersion及原balance摘要。

每次写同时持久化finance_operation、money_account版本/余额、不可变money_entry/原引用、audit、outbox；使用同费用组锁防止报价/审批变更和资金命令穿插。资金版本独立，不修改Q/C/H/B。等式：有效实收-已核实退款=正常结算+多收待处理；正常结算=各账单min(该账单应收,该账单净实收)之和，多收不得自动挪作另一账单收款。部分退款后余款继续挂账。无“结清才能关闭”条件。

## 权威缺口与交接

`FinanceAccess.ledger`接口默认503；生产TrustedFinanceAccess已接当前身份/CSRF/企业scope，员工精确要求finance.money.read/assess/adjust/collect/reverse/refund对应能力，不能从quote.write推断。客户仅按已有当前企业ADMIN规则读取摘要。资金事实verify仍按类别503 FINANCE_COMPLETED_APPROVAL_FACT_PENDING / FINANCE_ADJUSTMENT_APPROVAL_FACT_PENDING / FINANCE_CORRECTION_EVIDENCE_PENDING / FINANCE_ACTUAL_REFUND_PROOF_PENDING / FINANCE_COLLECTION_PROOF_PENDING，不能凭新增候选API获得财务权限或伪造已核验事实。BE-0/各事实owner需接入：当前finance身份/细分能力与公司scope；不可变完成审批+完整覆盖摘要（含客户授权/执行事实）；应收降低客户确认及财务审批；私有凭证当前归属/安全可用；实际到账、错录调查、已完成外部退款核验。禁止浏览器approved/verified布尔替代。`JdbcMoneyLedger.Authority.verify`在持锁后、原键回放前调用，并绑定完整命令摘要；缺少任何事实503，权威事实不符拒绝。

V3/V4为追加迁移；原V1/V2不改。运行账号仅新表SELECT/INSERT和money_account必要列UPDATE，原group锁沿原最小授权；不授予不可变流水UPDATE/DELETE。旧库由公共集成所有者显式迁移，本车道只在fresh3307测试schema验证。不自动启动outbox消费者，当前仍本地事实格式，标准事件接入由公共集成处理。

## 2026-10-24生产候选补充（真实集成未验收）
- 资金入口使用MONEY_READ/MONEY_WRITE purpose及finance.money.*能力，不要求finance.quote.write；写操作仍在线CSRF/员工启用部门/当前企业scope。
- collect/refund是财务明确核验线下资金的人工登记，不代表已接银行。原命令金额、参考号、日期、方式、原收款与私有凭证同money操作事务写入V6 finance_manual_money_fact；审计/账/outbox失败全部回滚，原UUID恢复不新增事实。私有文件只证明凭证上下文可用，文件本身不证明到账。
- 新POST /api/finance/groups/{group}/money/rebook 精确键 operationId/expectedVersion/expectedGroupVersion/originalPaymentId/correctionEvidenceId/reason/billId/paymentId/amountCents/voucher；voucher沿既有5键。必须原收款有效、未退款，replacement的correctionOf服务端固定原收款ID，同一参考号、同一账单，实际重记金额必须非零且不超原应收。要求finance.money.reverse及finance.money.collect，current文件及原P completion可信核验。一次事务保留原收款、冲正标记和非零替代事实，只增一次账版本，不先单独冲正为零。真实退款仍独立refund，不用rebook冒实际退款。
- P completion通过finance-approval POST /internal/approval/fee-completions/query四字符串键tenantId/companyId/groupId/completionId唯一取；F核原quote/digest/version/永久财务分支/主管与必需财务回执/规范coverage/原ticket round关系。
- 私有凭证finance-ticket POST /internal/ticket/finance-voucher-fact精确8键sessionToken/ticketId/groupId/companyId/businessId/fileId/purpose/evidenceDigest；FINANCE_COLLECTION或FINANCE_REFUND；核原owner和sha/state AVAILABLE。
- 已批准应收Assess仍缺真实执行owner事实，生产503失败关闭；Lower和单独Reverse仍需对应可信调整/错误事实，不把本候选组件测试或私有文件存在当完整收费通过。

## 2026-10-26 BE-F V8资金闭环候选

V8新增不可变 finance_money_terminal_fact。collect/refund/reverse/rebook与资金账户、原operation、人工核验事实、审计和outbox同一物理事务提交。每条终态事实固定当前tenant/company/group、actor、bill、原completion、账户版本、业务ID、原收款、金额、私有凭证标识/摘要、错录证据及完整命令的 authorityDigest。表只授予运行账号SELECT/INSERT；禁止UPDATE/DELETE。文件只提供私有凭证上下文，资金发生由具备当前细分能力且在当前企业scope内的财务人员显式提交并留痕，不接银行接口。

部分收款允许小于应收；经人工核验的实际到账允许大于本账单应收，超额进入 overpaymentCents，不得自动抵扣另一账单。退款只核销已经实际线下完成且有私有凭证的金额。reverse仅允许权威错录事实确认 actualUnreturnedCents=0；非零资金必须使用rebook在一次事务中保留原记录、冲正标记和替代收款，失败不得出现中间零余额。

通知服务只消费finance outbox的以下终态事件。字段均为字符串，除明确可空字段外不得缺失；消费者按tenant+operationId幂等：

- FINANCE_COLLECTION_FINALIZED：collect、reverse、rebook终态。
- FINANCE_REFUND_FINALIZED：refund终态。
- payload固定：schemaVersion=1、operationId、tenantId、companyId、groupId、billId、completionId、accountVersion、action、businessId、originalPaymentId（普通首次收款可为null）、amountCents（零资金冲正为0）、authorityDigest、executionReceiptsDigest、occurredAt（UTC ISO-8601）。executionReceiptsDigest来自原应收入账时W返回的逐行LABOR/OTHER执行receipt集合规范摘要，不由资金请求重填。

这是生产者候选字段契约，不表示通知服务已完成适配或公共组合已验收。owner缺失、owner返回404、超时或不可用统一以503失败关闭；原操作结果未知时只查询原operationId，同UUID完整参数恢复，变参冲突。

