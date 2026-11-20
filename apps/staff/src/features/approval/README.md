# 审批前端接入（FE-0）

原FE-P/fe_approval_finish的5文件冻结后，由前端开发顾问按任务14.18～14.20接续；公共路由、现行会话/CSRF和对象租约仅FE-0单写。其他执行者的notification局部文件不在本模块所有权内。

当前入口是 `/staff/approvals/parts`、`/staff/approvals/parts/:applicationId`，旧 `/approvals` 路径归一为兼容别名；`/tickets/:id/parts`直接核验当前工单。ApprovalsView.vue是兼容壳，保留的旧实现见原运行证据，不存在第二套生产目录路径。

沿现行HttpOnly Cookie会话和request函数，不修改登录首次确认Cookie、me→csrf顺序。POST仅用当前AUTH CSRF且attempt相符；请求同时绑定身份generation/attempt、组件、路由/选择上下文、slot及原操作current。旧401/503不能影响新身份、重挂载或新操作；不自动重发。内存只保原操作和冻结参数，不落Cookie/token/password，不跨actor/tenant呈现。

ObjectLease为30秒展示租约、25秒续查；失效/读取失败/隐藏清正文、选择和确认，卸载原操作仍可由同账号只读查询。原404须精确OPERATION_NOT_OBSERVED，原参数与当前已核验选择一致并明确确认才可重交；401/403/503和对象404不允许恢复写。成功旧回执不是当前申请状态，只提供显式当前查询链接。

按现行approval.parts.v1 1.0.1-slice.2使用assignment正BIGINT字符串departmentId；当前本人IN_PROGRESS/VALID assignment才出现创建入口。仓库/备件按stock/api-catalog-v1.json查询当前ticketId/assignmentId及独立分页，元数据不冒充库存余额；目录失败不放行。列表按当前ApprovalController/Queries的required ticketId分页，只显示当前授权工单申请，不伪全租户队列。详情按服务端allowedActions，缺失不猜主管角色。列表与allowedActions生产代码已有，需BE-P同步原JSON协议，不以旧JSON遗漏反向删掉当前实现。

单项提交、决定、撤回和原UUID恢复已挂组件；审批通过不表示已预留、出库或修好。多明细、完整审批规则、收费/库存执行及完整P01～03并未因此完成。接口候选已接不等可信六服务/TLS/真实身份已联调。

验证入口 scripts/fe0-approval-ui-check.mjs。Vitest/类型构建、实际Chrome的模拟HTTP和真实后端分层。模拟目录可通过组件端口显式注入；另有按真实stock URL拦截响应的测试，均非真实数据库或Cookie/CSRF联调。当前分轮失败、修正结果和运行窗口以协作记录/阶段0/FE-0运行证据/20261021及本角色沟通记录为准。
