# 费用审批 PART 覆盖事实 v2（候选协议）

状态：跨服务设计草案，尚未冻结、实现、部署或通过验证。v1 的历史摘要和 completion 不改写。本文供 finance、approval、ticket、stock 四个所有者共同实现；不得仅凭本文放开收费执行。

## 目的与不可推断的历史

当前 finance 报价行仅含 id、ticketId、roundId、kind、amountCents；approval 将该五字段固化到 completion。备件申请另有仓库、物料、数量，但没有指向报价行的不可变事实。不得通过金额、名称、时间或数量猜测关联，也不得给历史 completion 回填授权。历史无绑定时收费 PART 保持失败关闭；要形成客户应收，须按当时业务事实创建包含完整绑定的新报价，客户重新确认并完成内部审批。已经发生的库存消耗及内部成本如实保留，未经授权部分不自动形成客户应收。

## 版本化行及编码

finance 新报价的 `coverageSchemaVersion` 为整数 2，所有行按 `id` 的规范 UUID 小写字符串字典序排列；每行仍保留原五字段，且仅 PART 行增加 `partBinding` 对象。v1 不增加任何字段、沿用原序列化和摘要；v2 LABOR/OTHER 无 partBinding。对象必须精确校验字段，不接受额外或缺失字段、重复 lineId、非规范 UUID、非规范十进制整数、溢出或空字符串。

`partBinding` 的全部字段：

| 字段 | 类型和意义 |
| --- | --- |
| `approvalId` | UUID 字符串，唯一指向已批准备件申请；不是费用审批 applicationId |
| `approvalVersion` | 正整数十进制字符串，指向不可变批准版本；零不代表已批准事实 |
| `warehouseId` | 正整数十进制字符串 |
| `partId` | 正整数十进制字符串，库存主数据稳定编号 |
| `quantity` | 正整数十进制字符串，授权件数 |
| `unitCode` | 1～20个字符的非空文本，无首尾空白或控制字符；逐字取库存物料权威单位快照，不由用户任意填写，支持现有中文单位 |
| `allocationRule` | 固定字符串 `FULL_LINE_SINGLE_USE_V1` |

行的 `amountCents` 为该行含全部数量的客户授权总价，正整数分，不使用浮点数。为避免零头、重复批次和当前执行模型的歧义，首版一条报价行仅对应一笔真实 USE，USE 数量必须等于 quantity、收费金额必须等于 amountCents；不能部分收费、按金额反算数量或拆单。若现场分多笔 USE，须在报价和客户确认前按实际计划分别创建行，并各自绑定唯一 USE；需要新增或变更则先重新形成报价、客户确认和审批。此限制是首版候选设计，需确认是否与实际业务需要冲突；未来分次使用需新版本规则，不能静默放宽。

一条备件申请只含一个物料和仓库，允许同一申请对应多行，但其同一 `approvalId + approvalVersion` 下所有行的 quantity 之和不得超过申请批准总量；必须有跨行、跨报价有效授权的原子剩余额度和失效/冻结规则，否则禁止多行放行。每个 USE UUID 只可被一个收费执行事实引用；行也只能被一个 USE 引用。相同物料不同仓库、不同申请或不同批次均以各自 lineId、approvalId、warehouseId 和 USE UUID 区分，不能合并推断。库存批次号若存在由 stock USE 事实提供并绑定结果，不可由报价推测。若无法证明跨报价额度，以一申请仅对应一条有效授权行为安全首版。

## 来源、摘要和确认

finance 创建新报价前，通过经过认证的 approval 权威接口核验备件申请已批准，申请租户、企业、ticket、round、仓库、partId、批准版本、数量和单位与请求一致；当前客户和操作人授权由现有 identity/ticket 共同核验。缺任一事实、跨租户/企业/工单/轮次、撤销或未知结果均失败关闭。接口具体方法与可信边见下；不能将客户端回填字段当权威。

finance 的 v2 `quoteDigest` 应以带版本的规范编码覆盖所有报价行及全部 partBinding，且覆盖原方案、企业、工单、轮次、group、报价版本和影响价格/客户授权的其他字段；同一客户确认记录必须绑定该精确 quoteId、version 和 digest。v1 的 quoteDigest 计算保持原样，禁止改写旧确认。approval 接收 finance 的可信上下文后，重新校验完整字段、客户确认和当前 scope；以带版本的规范编码将行与 partBinding 全部纳入 `coverageDigest`，并在不可变 completion 固化完整行、coverageSchemaVersion、quoteDigest、confirmation 和主管/必要财务审批回执。任何字段变化必须是新报价并重新确认和审批。

规范字节定义由各 owner 共同冻结：UTF-8 JSON，字段名和类型固定，对象键按 Unicode 码点顺序、数组按 lineId 排序、不含多余空白，UUID 小写标准格式，数字事实均按上述规范十进制字符串；字符串采用标准 JSON 转义，使用 SHA-256 小写十六进制。版本字段进入摘要，避免跨版本碰撞。各实现用共享正负黄金向量验证，不能改造 v1 摘要算法。

## 精确可信上下文候选

已有 `POST /internal/finance/fee-approval-context` 请求字段为 sessionToken、groupId、quoteId、ticketId，边为 approval-finance；v2 响应在现有响应上提供 `coverageSchemaVersion=2` 及上述 lines，保留 tenantId、companyId、ticketId、roundId、groupId、quoteId、quoteVersion、quoteDigest、generation、customerConfirmation、current、checkedAt。approval 不接受客户端提交的 coverage 替代可信响应。

现有 `POST /internal/approval/stock-context` 的具体实现接口及可信调用方需在实现前核对；新报价保存时需要一个不依赖仓管角色的、针对业务提交人的精确 approval 权威查询。候选请求仅含 sessionToken、approvalId、ticketId、roundId、warehouseId、partId；响应含 tenantId、companyId、ticketId、roundId、approvalId、approvalVersion、warehouseId、partId、approvedQuantity、unitCode、approvalState、checkedAt，并应证明申请人与当前工单责任关系及企业范围。此接口路径、权限与该列表为待 owner 冻结方案，不可假装现有接口已提供 company 或 unit。存在租户、部门、角色、工单和申请资格实时核验；不允许由 finance 跨库读取 approval/stock 表。

ticket 的当前上下文至少提供 tenantId、companyId、ticketId、roundId、当前 assignment、工单状态、actor 与服务端 freshness，并在签发 durable 执行许可时与转派、关闭和报价冻结串行。stock 验证 approval completion 精确 PART 行、相同绑定、真实 USE/仓库/数量/物料和 ticket permit；远程调用在本地库存事务之外，执行事务内进行 generation 屏障、USE 和唯一性检查，不持数据库锁等待网络。未知结果按原 operation UUID 查询与恢复，不能释放未知 permit。

## 最低验证

验证 v1 摘要兼容及无绑定旧事实拒绝；v2 不同审批、仓库、物料、数量、单位或金额任一篡改均改变摘要并被拒；客户确认旧摘要不能授权新报价；跨公司、跨轮次、已撤权、旧代际拒绝；多行重复申请额度、重复 USE、不同仓库同物料、真实 USE 数量不符、部分使用、整行金额和整数溢出均有反例。分别提供组件、真实 MySQL、服务间 TLS 与并发/丢响应恢复证据；历史成本与库存不因拒绝收费被删除。
