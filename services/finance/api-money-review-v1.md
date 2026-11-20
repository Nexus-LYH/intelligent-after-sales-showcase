# 资金调整与错录调查候选接口 v1

候选状态：必须先应用 finance V11、ticket V21、最小授权和网关路径；组件验证不代表真实链路验收。所有写操作使用原 `operationId` 与 `Idempotency-Key`，JSON 严格键集合、Cookie 与 CSRF 沿现有约定；金额分及版本用十进制字符串。

`POST /api/finance/groups/{group}/money/reviews`：字段 `operationId,reviewId,kind,accountVersion,groupVersion,subjectId,billId,lineId,proposedCents,coverageDigest,evidenceFileId,evidenceDigest,reason`，无值字段显式 null。申请人是当前企业范围内的员工。REDUCTION 要求 `finance.money.adjust`，subjectId=lineId，coverageDigest 等于账单原完成覆盖；evidenceFileId/evidenceDigest 为 null。CORRECTION 要求 `finance.money.reverse`，subjectId 为原收款 ID、lineId/coverageDigest 为 null，必须先以 `FINANCE_CORRECTION`、businessId=reviewId 上传 ticket 私有调查文件并传入其 fileId/SHA-256；proposedCents 是调查人员依据材料提出的实际未退金额，必须独立复核后才能成为事实，单次提交并非证明。

`GET /api/finance/groups/{group}/money/reviews/{review}`：当前客户企业管理员只读取调减资料；当前企业范围员工可读调查资料和私有文件 ID。`GET /api/finance/groups/{group}/money/reviews/{review}/operations/{operation}` 仅给原操作人查询记录。404 只表示未观察到，不代表写入一定未发生。需要重试时由用户显式使用原 UUID 和原参数，不自动提交。

`POST /api/finance/groups/{group}/money/reviews/{review}/confirm`、`approve`、`verify` 请求体仅 `operationId`。confirm 由当前客户企业管理员完成；approve 由与申请人不同、拥有 `finance.money.adjust` 的当前财务员工完成且必须先 confirm；verify 由与申请人不同、拥有 `finance.money.reverse` 的当前财务员工在核查私有调查材料后完成。申请及每次决定重新核验身份、企业范围、凭证可用性及原账本/组版本，事件与 operation 同一事务仅追加，版本变化后必须重提。当前无撤销/拒绝入口，决定产生前可不予确认/批准。

调减 `money/lower` 使用 reviewId 作为 `adjustmentApprovalId`，其他字段须与已完成审核完全相同；已发生收款不因调减消失，多收留作负债。错录 `money/reverse` 或 `money/rebook` 使用 reviewId 作为 `correctionEvidenceId`；独立复核为零才允许单独冲正，非零只能一次事务重记同账单、原参考号、精确核实金额及有效收款私有凭证。每次执行仍核验当前资格，真实外部退款需走 refund。

上列接口是最小人工核查工作流：PRD 未具体规定错录调查人员划分，本候选采用不同员工独立复核。资格及真实性来自用户人工判断与当前私有文件，不声称与银行自动核对。缺少 ticket 可信事实、超时、404、字段错配、未知状态均失败关闭。
