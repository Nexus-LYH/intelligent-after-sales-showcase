# F source-intent / ABORT 候选契约（14.27，2026-10-23）

finance单写；公共边、W/P/S协调由root接入。V5追加迁移，不覆盖V1～V4。历史C轮86及规则恢复84+3跳过证据保留；2026-10-24当前候选D轮已完成84组件+3真实MySQL=87/0/0/0。生产TLS边/送达/W-P-S全局ACK尚未接入，未称跨服务或联合验收通过。

源状态唯一取值IN_PROGRESS、COMMITTED、ABORTED；W core原PREPARING要求应由其owner显式改为核验F的IN_PROGRESS，不能把404/超时映射为ABORTED。COMMITTED只是F本地业务提交，不是全局冻结ACK。终态不可反转或复活。

每次报价POST先独立持久源意图，完整保存原Submit+QuoteDetails，并绑定tenantId、actorUserId、companyId、groupId、operationId、quoteId、expectedGroupVersion及commandDigest。digest沿现有quoteDigest（覆盖全部明细/替换/金额/工单/轮次/收费说明/规则版本阈值）。仅持久成功后才能请求W prepare；权限/目标当前scope先验证。源意图不能直接授权收费执行。

报价事务与ABORT都先锁同fee_group，再核源绑定/状态。报价、原receipt、audit、原outbox及源COMMITTED/resultDigest/源事件/源outbox同物理事务提交；ABORT源终态/原因/事件/outbox也同事务。晚到提交遇ABORTED拒绝；ABORT遇COMMITTED返回原COMMITTED，不伪装取消。COMMITTED原UUID仅返回原结果，不再次prepare旧代际。DB/网络未知保留原参数查询，不推断绝对未执行。

可信纯查询：POST `/internal/finance/quote-sources/query`（POST仅为完整binding传递，处理纯读）。仅精确ticket-finance TLS/HMAC边；不允许gateway/浏览器伪头或session代替服务信任。严格字段：tenantId、actorUserId、companyId、faultRelationId、ticketId、roundId、groupId、expectedGroupVersion、quoteId、operationId、commandDigest。BIGINT均十进制字符串，ID规范UUID，digest64位小写hex。绑定每项精确匹配，并核报价保存的ticket/round覆盖及faultRelationId推导的finance group。返回同binding + sourceState/state、resultDigest（未提交null）、checkedAt。缺失404仅当次未观察到，不能释放W栅栏；不匹配409，不改变任何事实。

公开纯查：GET `/api/finance/groups/{group}/quote-sources/{operation}`，原actor+当前read/scope。返回安全binding、状态及resultDigest，不返回报价正文/私有凭证。无记录404不代表旧POST不会迟到。

显式ABORT：POST同前缀`/abort`，当前write/CSRF，Idempotency-Key=原operation UUID；精确正文operationId、quoteId、expectedGroupVersion、commandDigest、reason（5～500）。仅已登记原actor/全binding，无记录404且不假称已封闭。ABORT成功终态持久保存，重复原完整参数返回原终态；COMMITTED胜出则返回COMMITTED，前端继续查询原业务receipt。不同ABORT原因不得覆盖第一次原因。没有TTL自动解锁/删除源事实。

`finance_quote_source_outbox`不可变终态事实，包含完整安全binding、目标ticket/round、sourceState、resultDigest；与源COMMITTED及原报价同事务写入。按已冻结协议由W持久pull查询源终态；F无push worker或DELIVERED标记，源outbox不冒充W ACK，协调终结须汇齐required-owner持久回执。

精确新增边/权限白名单由root公共集成，不由本车道修改trust。未接公共边时可信端口失败关闭；未具备W/P/S全ACK仍拒绝实际报价提交。历史无源COMMITTED旧报价需受控核对，不伪造IN_PROGRESS倒填后允许旧执行。

运行最小权限：finance_quote_source/event/outbox只SELECT/INSERT；仅source额外授UPDATE(source_state,result_digest,updated_at)，immutable event/outbox不能UPDATE/DELETE或FOR UPDATE。V5及Boot启动守卫由公共所有者显式迁移后才换默认制品。

TrustedFinanceAccess已改调用W /internal/ticket/finance-quotes/prepare，正文含sessionToken/csrfToken及绑定字段。必须匹配原请求，且coordinationState=FROZEN、globalFreezeAck=true、quoteWritable/ executionBarrierClear=true、freezeId、正generation、requiredOwners恰为approval和stock；现有FENCING返回继续503。只有COMMITTED原UUID回放跳过prepare，仍查当前身份/scope。

验证入口：协作记录/阶段0/后端集成运行证据/20261023源意图。A失败保留（测试CHECK未限定原操作，已有终态导致DDL拒绝）；B85全绿，C增加提交未知回归后86全绿。最终C facts：7源/14事件/7源outbox，3COMMITTED/4ABORTED/0IN_PROGRESS，0临时故障。此为新样本/明确注入身份和owner事实，不是支付退款或物理commit-kill。

规则发布后的原键恢复：已登记源的报价POST复用服务端保存的原PublishedRule，首次登记仍核正式规则。后续发布不得给同UUID换摘要/阈值；客户端任何正文变化仍409。此增量以独立快照和新增HTTP回归验证，原C轮86快照及制品原位保存；当时新版3MySQL未重复执行；2026-10-24 D轮已补齐87整轮，历史未执行证据仍原位保留。

## SOURCE-DELIVERY-V1（2026-10-24 10:07:48，按技术负责人记录冻结）

生产可靠投递责任采用 **W 持久 pull**。W 以服务凭据调用现有精确 ticket-finance POST `/internal/finance/quote-sources/query`，finance 仍按原11字段、原actor/UUID/完整commandDigest精确返回IN_PROGRESS/COMMITTED/ABORTED。COMMITTED有64位resultDigest，ABORTED为null；404或超时不证明绝对未执行，不能解除栅栏。没有新ABORT push路由。

finance不再等待未定义的push端口，也不并行实现F push worker/delivery表。原finance_quote_source_outbox保留不可变源终态事实，不因查询成功、HTTP200或W收到源事实标记DELIVERED。源终态与清理送达分开；报价提交/ABORT及原终态outbox同事务规则不变。W拉取、lease/attempt/recovery与P/S公共协调由技术负责人负责，本车道不接管公共文件。

W只有IN_PROGRESS且当前双owner ACK和原permit清楚才可FROZEN。COMMITTED/ABORTED先RECONCILING，汇齐两owner持久终态回执后才RELEASED。F不根据源COMMITTED、空扫描、expectedVersion=0或部分ACK推断全局完成；source GET/ABORT查询原键语义不变，不借旧Cookie继续业务，不TTL自动释放。

既有 finance-ticket POST `/internal/ticket/finance-quotes/source-commit`仅为COMMITTED兼容触发，不作为本阶段finance生产自动push通道。冻结的W兼容回执采用完整binding、sourceState/resultDigest/coordinationState/checkedAt；原结果稳定关联为freezeId+generation+operationId+commandDigest+resultDigest，不额外要求新receiptId。ABORTED不得发至此端口。RELEASED且原终态精确匹配才表示owner汇齐，但F当前没有DELIVERED表/worker，不能伪记送达。

确认依据：协作记录/技术负责人顾问-沟通记录.md 2026-10-24 10:07:48、10:09:31；真实组合证据位于公共集成运行证据/20261024FinanceFreeze。本车道只接收协议决定，未独立复验其真实运行结论。历史finance87实际MySQL和F/W parser探针4项继续单独引用，不扩大为全费用/资金通过。此前本文件候选push/新receiptId/delivery建议已被本节替代，历史讨论仅保留在追加式沟通记录，不与现行规则并行。


## 审批完成事实H同步（2026-10-24生产候选，尚待真实集成）
- 唯一调度仍W durable pull，不建F push。P group端口返回tenantId/companyId/groupId/completions/checkedAt；W独立调度获取原COMPLETED facts，再调用F。
- ticket-finance POST /internal/finance/fee-completions/record，精确4字符串键tenantId/companyId/groupId/completionId；必须可信TLS边，无用户Cookie/CSRF，不借原actor会话。F反向P四键query验证原completion、原F quote/version/digest/coverage、实际节点receipt。actorUserId为P完成动作真实决定者，仅作事实审计归属。
- F同group锁事务：服务端确定性recordingOperationId=UUID.nameUUIDFromBytes UTF8('finance:approval-completion:'+tenantId+':'+groupId+':'+completionId)，原ApprovalFact、H=max(H,原B)、永久财务标记保留、V7不可变mirror/receipt、operation/audit/outbox原子提交。复查原completion成功后原键返回原recordedAt/groupVersion/receipt，checkedAt为新检查时间。
- 响应tenantId/companyId/groupId/completionId/quoteId/quoteVersion/recordingOperationId/groupVersion/highestApprovedCents/everRequiredFinance/recordedAt/checkedAt，除everRequiredFinance布尔外均字符串。404/超时/不匹配不记成功，不等于真实资金完成或outbox已投递。
- 新报价在W双owner FROZEN返回之后、F构造新basis之前纯查P该组所有COMPLETED，缺本地同fact digest mirror即503 FINANCE_APPROVAL_HIGH_WATER_MARK_SYNC_PENDING。原source保IN_PROGRESS/原W围栏不解；W独立mirror完成使原expectedVersion过时，则原参数409，须显式原UUID ABORT后新UUID/新组版本，不自动改原参数。F报价链内不同步回F写H，避免同组自锁。
- FEE_READ context.current额外要求当前group B<=原quote冻结B；C上升导致原basis不足时current=false，保留原basis，不拿当前成本重写原quote。

