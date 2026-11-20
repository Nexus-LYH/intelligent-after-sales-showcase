# 库存前端接入（任务14.24/14.26）

依据 stock/inbound-api-v1.json、execution-api-v1.json（含warehouseCatalog）及 api-catalog-v1.json。前端单写本目录、专属测试/脚本及router/App必要入口；不改后端、迁移、权限或运行chain。

入库 `/staff/stock/inbound` 与兼容深链 `/staff/stock/warehouses/:warehouseId/inbound` 都接真实仓管目录：GET `/api/stock/warehouse-catalog?page&size`、`/warehouse-catalog/{warehouseId}/parts?page&size`。当前stock.read+显式warehouse scope在线过滤，包含启用且尚无余额的备件，返回名称/型号/单位。已撤销此前完整目录缺口；不借工程师ticket目录、不把深链本身当权限。旧balanceBackedCatalog仅保留兼容辅助函数，生产页面不再调用。

仓管 `/staff/stock/executions`、`/staff/stock/warehouses/:warehouseId/orders/:orderId`；工程师 `/staff/stock/engineering`、`/staff/stock/engineering/orders/:orderId`。orderId是已批准备件申请UUID引用，服务端核验原批准、当前轮次/assignment/责任与权限；契约没有仓管执行队列API，不猜列表接口。仓管目录先核scope；当前单GET404不等于无执行，只有显式首次按批准申请的版本0命令，仍由服务端审批/执行栅栏决定。403/503不授予首次入口。

整单reserve只改reserved；不足WAITING没有部分预留；截止取成功24h与批准7d较早值。issue只扣一次现存量与预留，当前明确FREE才可执行；UNKNOWN/收费缺权威仍503。工程师uses只记账不二扣；原单return仅退尚未消耗数量。当前order与原UUID历史receipt分开展示。每动作ACK、ACCOUNTED或RETURNED都不说明所有批准来源已清理，不表示工单可关闭，页面不提交解除关闭屏障。

上传UUID与入库UUID独立，签名PNG/JPEG/PDF、20MiB与原File SHA256；私有下载当前鉴权。所有执行动作冻结原UUID、版本、目标及完整参数，未知只查原键；GET404只是本次未观察到/也可能无权，核验后显式同键继续。401/403/503不放行，已知成功后404不重新开写，持久REJECTED回执按拒绝显示。原参数仅内存保留，刷新/关页不承诺恢复，不声称服务端凭证已删除。

回调归属绑定tenant/actor、身份代际/attempt、组件、仓库或工程师通道、不可变原批准orderId与操作租约；旧success/failure/finally不得清新pending。当前对象30秒租约/25秒续查；隐藏/失权/依赖失败清当前正文和确认，旧身份原操作不外露。服务端原批准及当前轮次/assignment检查保持，没有在缺少投影字段时伪造客户端轮次权限。

验证入口：协作记录/阶段0/库存前端运行证据/20261023执行/验证摘要.json。真实Chrome14项是默认八服务自验，业务前置夹具走真实公开HTTP，执行写通过实际页面；浏览器传输abort/丢503/迟到不是物理commit-kill。期限24h/7d真实等待、收费正向、全部清理与独立页面验收未执行；后端独立验收层单独引用，不挪作页面通过。早期脚本错误、首轮预留409（原code未保存，未确诊原因；原键只查404并保留样本）及分轮证据保留。新夹具增加审批前置等待后通过，不能据此断言旧操作从未执行。

