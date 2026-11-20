# 费用报价页面（2026-10-24接续）

当前员工/客户复用FinancePage：/finance、/tickets/:id/finance及/quotes/:quoteId。当前同源会话与CSRF transport，工单入口按finance.quote.read显示；引用本身不授权。员工显式开组/编制收费报价，替换项须明确选择；客户当前企业ADMIN核验后明确原组版本与不可变报价版本确认。页面与内存投影白名单丢弃internalBasis，不把客户确认/本地审批意图当内部审批、应收或资金。

目录采用root冻结GET /api/ticket/tickets/{ticket}/finance-quote-references，20条generation倒序及nextBeforeGeneration。只代表已COMMITTED引用；点击后F原quote GET重新核即时状态/权限，不能假作当前有效报价。旧原契约“无发现入口”缺口由此补齐，真实运行以本轮证据为准。

原UUID/完整body仅当前运行内存保留，按tenant/actor/ticket归属。未知先查原operations，报价404再查quote-source；source404不证明未执行，核验当前对象后才可显式原UUID原参数继续，401/403/503不放行。IN_PROGRESS可显式继续或原UUID/quote/version/服务端digest/原因ABORT；未知ABORT保留首次原因且不再发原报价；COMMITTED只查回执，ABORTED不代表双owner已释放。身份代际、attempt、原操作lease、组件存续和30秒显示租约保护；隐藏清正文，25秒纯读续查不自动写。关闭/刷新浏览器不承诺保留草稿。

验证入口：协作记录/阶段0/费用前端运行证据/20261024/验证摘要.json。finance-real-check.mjs仅正式领取窗口并设IAS_FINANCE_REAL_WINDOW=1后执行。实际收退款/收费领料仍不放行，费用审批任务不属于本页切片。以下为独立草稿阶段历史说明，已由本节当前接入状态替代，不与旧“完全不发请求”结论并列。

依据finance/api-quotes-v1.json。只交十进制元/分转换、最多50行的草稿校验、不可变请求构造和组件预览，不挂虚假生产入口、不发请求、不复制阈值规则、不把本地报价视为审批/应收/收款。`QuoteDraftComposer`需由已核验的费用组/当前报价宿主提供上下文；无上下文只能编辑，不能生成提交命令。

真实接入缺口：BE-0确认finance新role/精确网关六路由/当前会话与CSRF/最小数据源迁移及运行窗口；identity/asset提供FINANCE_READ/WRITE与quote.read/write/confirm、当前ACTIVE企业管理员事实；ticket提供当前服务快照公司/同故障relation/round和绑定完整摘要的代际执行屏障；approval提供唯一已发布rule/version/T及真实费用审批节点与完成事实；N/P把本地请求接标准同事务信封/可靠ACK。候选owner端点不是已确认生产端点，不猜路径或本地T/approved/freezeClear。

当前JSON未包含客户拒绝、草稿撤回冻结、审批完成生产适配、应收收款冲正/多收退款等完整生命周期，不自行扩充。客户投影不得含C/H/B/财务阈值与首次触发；报价确认仅当前企业管理员且当前报价/组版本，原UUID未知恢复另随生产接入实现。不将该独立片说成费用页面或真实业务完成。
