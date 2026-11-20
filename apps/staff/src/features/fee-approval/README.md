# 内部费用审批页面

路由：/fee-approvals、/tickets/:id/fee-approvals、/tickets/:id/fee-approvals/:applicationId。仅EMPLOYEE且approval.fees.read显示入口；真实资格不从菜单、角色或客户确认推断。

原工程师从W fee-quote-references授权引用目录选择，P quote-context精确原ticket/group/quote纯查安全预览。canSubmit/current/ownerAcksComplete与existingApplicationId控制本次提交按钮，POST仍由服务端再次核验。新申请只发operationId/applicationId/ticketId/groupId/quoteId，不能把预览basis、确认、reviewer或资格写回。已有申请跳原详情，不重建applicationId。

列表和详情逐次在线授权；仅当前detail.actions可开放refresh/approve/reject/withdraw，前端额外禁止申请人自批。refresh是显式写，既不是GET副作用也不改冻结金额/规则。客户未确认可发起后WAITING_CUSTOMER，不能把客户确认当内部完成；COMPLETED只表示内部审批，不代表收款或执行owner已授权。TERMINATED仅历史无写。

写与原操作GET的actions必须为空，历史回执只确认原动作，不覆盖当前详情。原命令按tenant/actor/ticket保存在SPA内存；unknown→原UUID纯查，404仅未观察到，须独立核验原target当前资格后才显式同键/完整原body继续。401/403/503不放行，不自动重试写；身份代际/attempt、组件存续、原操作lease保护旧回调。30秒显示租约、隐藏清正文和25秒纯查保持当前权限。关闭浏览器不承诺保存原命令。

2026-10-25已接收暂停FE原9文件快照，并按root新FeeActivation八服务manifest核验后完成真实页面8/8自验；当前审批API已可用，不再以旧“未上线”状态排队。费用明细/冻结代际/coverageDigest展示，COMPLETED必需主管及按分支财务/完成回执，禁止仅state字符串冒完成。五身份真实新登录，低额主管完成/高额独立财务完成、原5键同UUID恢复、丢503仅查、客户隔离/撤回/拒绝均实测。证据：协作记录/阶段0/费用审批前端运行证据/20261025/验证摘要.json。当前367单元、双端类型/build与全API拦截6项另列；拦截不是后端或独立验收。真实只读H镜像分库事实、物理提交故障、旧代际竞争/终止、资金凭证/收退款/付费执行及独立页面验收未在本轮完成。页面只表示内部审批，不以completion直接放行执行/资金。窗口已释放，保留新8服务/3307和本轮5173/5180，旧隔离5174不接管。

