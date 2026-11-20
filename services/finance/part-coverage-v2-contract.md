# PART 报价覆盖 v2（候选，未开放客户付费）

现状：客户付费 PART 的新报价提交和客户确认在真实 owner 核验、跨报价唯一性及屏障协议完成前返回 503 `FINANCE_PART_COVERAGE_AUTHORITY_PENDING`。历史 v1 可读取，不能凭 v1 无绑定的完成事实授权新的 PART 收费。已提交原 UUID 的历史来源和快照不重写。`reverse/rebook` 另因独立金额核验缺口保持 503。

v2 PART 行保留 `id,ticketId,roundId,amountCents,kind`，追加 `partBinding`，其全部键均为字符串：`approvalId`（小写规范 UUID）、`approvalVersion,warehouseId,partId,quantity`（正十进制整数字符串）、`unitCode`（stock 物料权威单位）、`allocationRule=FULL_LINE_SINGLE_USE_V1`。一行一次 USE，精确数量=授权数量，金额=该行总价；不按单位价格推断或拆分。一个新报价中同一 approvalId 最多一行；跨报价唯一还须 owner 的持久屏障保证，非本地 Map 去重可以证明。

v2 报价提交行携带 `partBinding`；客户报价读取及 finance→approval fee context 对有绑定报价返回顶层 `coverageVersion="2"` 和包含绑定的行。历史无绑定 quote 不添加字段。受信源尚未完成时 v2 客户报价不会实际提交或确认。

## 两种 SHA-256 的精确规范

quoteDigest 使用 UTF-8 的紧凑 JSON；序列化数组 `[owner,"QUOTE_SUBMITTED",parameters]`，owner 字段依次为 tenantId、customerCompanyId（均 JSON 整数）、faultGroupId（UUID 字符串）。parameters 沿用 v1 依次为 `[quoteId,expectedVersion,sortedReplacements,lines,ruleVersion,thresholdCents,chargesCustomer,pricingDescription,kinds]`，UUID 均小写字符串、版本与金额为 JSON 整数，lines 保留输入顺序，每行 `[lineId,ticketId,roundId,amountCents,executed]`；kinds 为 lineId 字典序对象。v2 在末尾再追加字符串 `PART_COVERAGE_V2` 和以 lineId 为键字典序排列的绑定对象，绑定字段也按字典序，数字均为字符串。v1 不追加任何元素，保持既有哈希。

coverageDigest 使用 UTF-8 紧凑 JSON，输入为按 lineId 字符串排序的覆盖行数组；递归将每个对象的键按字典序排列后计算 SHA-256，数组顺序保留。v1 行为原有五字段；v2 PART 行额外包含上述完整 partBinding；v2 其他行仍为五字段。所有规范字段值为字符串。P 的不可变 completion 必须返回同样的行、coverageVersion 和 digest，finance 的 `TrustedFeeCompletion` 才能比对。

固定向量：tenant=1，company=2，group=`00000000-0000-0000-0000-000000000001`，line=`...0002`，ticket=`...0003`，round=`...0004`，quote=`...0005`，operation=`...0006`，approval=`...0007`（均完整 UUID，前缀为 `00000000-0000-0000-0000-`）；expectedVersion=0，line amount=1000、executed=false，ruleVersion=`v1`、thresholdCents=200，chargesCustomer=true，pricingDescription=`test`，approvalVersion=1，warehouseId=2，partId=3，quantity=4，unitCode=`EA`。`EA` 仅是测试值，不代表现实 owner 已证明。quoteDigest=`4184f15b6911420882e4fb14ace1632fca3c11bed748de337e8d69ff826f05c2`；coverageDigest=`fcdc9f05641cf71178c8bf3eeec685b8804c59a083d592e68c041e183f925f04`。运行 `PartCoverageV2Test` 可复核。

## 必需真实 owner 与边界（待冻结，以下是要求，不是已实现接口）

1. approval 提供安全可信的不可变审批事实，明确精确 tenant/company/ticket/round、approvalId/version、warehouseId、partId、quantity、CUSTOMER_PAID、已批准状态与当前代际，以及签发者和当前对象范围；finance 不从金额、名称、时间或库存余额推断。
2. stock 提供可信物料单位与订单事实，包括 tenant、company、ticket/round、审批 ID/version、warehouse/part/quantity/unit、stock order ID 和真实 USE；stock order ID 不假定等于 approvalId。各边请求需携带原操作 UUID 与 command digest、响应带 checkedAt，使用明确可信身份和精确绑定，错误/超时/404/旧代际失败关闭。精确 endpoint、token 和信任配置由 P、stock、ticket 和集成共同冻结，本文不冒充已存在的路径。
3. owner 同事务持久保证同一审批申请跨报价只有一个有效授权行、冻结与在途执行安全序列化，并支持原 UUID 结果查询与屏障恢复；旧未完整授权由业务人员按真实事实新报价和客户确认，不自动补造。
4. ticket 提供当前 assignment/round/status 与持久 permit；P 将 v2 绑定写入不可变 completion；stock 按 permit、completion、USE 严格串行登记。跨服务协议和真实并发验证完成前，不开启收费路径。
