# PART 收费执行跨服务授权：候选协议与阻塞

状态：设计候选，未部署、未验证、不可用于放开生产路径。当前收费 ISSUE/USE 与公开 PART 登记保持 503。

2026-10-28 补充：`PartCoverageV2Candidate` 仅为 v2 单行精确绑定的离线候选校验器，尚未接入生产调用，也不验证 completion 权威、签名/摘要、配额或 permit。隔离副本组件测试 3 项通过；规范编码黄金向量、MySQL、可信边和并发均未验证。`StockExecutions.execute` 锁内二次 `access.authorize` 及 `charge` 调用仍为开放缺口，必须由持久 owner permit 协议替换后才能安全放行。不能以该校验器测试通过作为收费许可。

## 已核实的缺口

- `TrustedStockExecutions.authorize` 对新 CUSTOMER_PAID ISSUE/USE 返回 `PAID_EXECUTION_AUTHORITY_NOT_AVAILABLE`；测试中人为注入空 `requirePaidCoverage` 不能证明生产授权。
- P 的 completion coverage 行由 `FinancePartExecution.partLine` 严格校验，现有字段仅为 `id, ticketId, roundId, kind, amountCents`。库存审批 `stock-context` 提供仓库、审批单、工程师及 `partId, quantity`，但两者缺少不可变的逐行关联。金额、同工单、时间接近或相同数量均不能证明映射。
- `FinancePartExecutionStore` 验证历史审批和本地真实 USE，持有库存 generation 锁，但缺少与 ticket 当前 assignment 变更串行的 durable permit。ticket 公共桥当前正确拒绝此操作。
- `StockExecutions.execute` 持有库存 generation/warehouse 行锁时再次调用 `access.authorize`；该调用含跨服务请求。未来实现必须拆开远程调用与本地事务，不能增加锁内网络调用或用第二次远程读取冒充原子授权。

## 审批所有者应补的不可变覆盖事实

由 P 负责定义带版本的 coverage：在现有 tenant、company、ticket、round、group、completion、quote、quoteVersion、generation、line、金额及审批回执之外，精确绑定 `orderId/approvalId`、`warehouseId`、`partId`、授权 `quantity`、计价单位及金额/数量关系。P 应保证授权行唯一并纳入 canonical digest 和 immutable completion；金额单位、数量分摊和一行对应多次 USE 的业务规则必须由 P 与需求确定，不得由库存猜测。

存量 completion 未记录这些字段，无法自动可靠重建。可由有权业务人员按照产品与审批规则重新形成具有完整绑定的新报价和不可变 completion；真实既有库存/成本应保留。具体如何补录历史授权、是否需客户重新确认，须 P 和产品依据业务事实确定；不得以金额匹配或历史快照推定授权。

## 独立 fee-part permit 草案

1. ticket 在同一事务锁定 actor 和 ticket，校验当前身份、企业、工程师、round、assignment、状态、有效 completion/generation 及同 actor 已完成的真实 USE 对应许可；插入独立 OPEN permit（与 USE 许可不是同一记录）。permit 至少绑定 tenant、actor、ticket、round、assignment、operation UUID、规范命令 digest、group、completion、quote/version/generation、line、order、use UUID、仓库、物料、数量和金额。重复同键同参返回原状态，变参冲突。
2. ticket 转派、关闭、取消、重开和报价冻结/换代对该 ticket 的 OPEN permit 进行同事务屏障校验。不得按超时自动解除，不能将缺响应或 404 推定为无执行。
3. stock 在本地库存事务外通过精确可信边验证 OPEN permit 和 P immutable coverage，再在本地事务内锁 generation 与仓库，验证无冻结、代际一致、真实 USE、绑定一致和唯一性；该事务原子写入执行事实、UUID 结果、审计及 outbox。不能持数据库锁等待网络。
4. stock 对已提交和确定业务拒绝分别保留不可变结果或墓碑；业务拒绝墓碑只能在可以证明事务未提交时创建。提交或回滚结果不确定时保留 OPEN，不生成失败墓碑。
5. 事务结束后异步以 tenant、operation、command digest、result digest 调用 ticket complete；ticket 幂等记录唯一结果并关闭 permit。丢 ACK 重试相同内容。查询仅对原 actor 且当前 scope 可见；恢复使用原 UUID 和原参数。
6. 以上需要实际端点、调用者权限、迁移授权、死锁顺序和负例由双方共同冻结并通过真实并发验证；该草案本身不是实现或测试证据。

注意：许可建立后若报价冻结先于 stock 提交，库存 generation 屏障与 ticket OPEN 的处理顺序必须有明确可恢复协议：旧许可在排空前不签发新代际，不得关闭未知结果，也不得因重新查询瞬时状态绕过屏障。
