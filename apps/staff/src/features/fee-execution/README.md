# 收费执行页面（FE-P）
本目录只实现任务14.32的 LABOR/OTHER 收费执行组件、严格投影和原操作恢复。共享 router、App、session、request-scope 由技术负责人接入，本目录不修改这些文件。

接入方必须提供服务端当前工单版本、round、assignment、已完成费用审批的批准行，以及当前工程师在同 ticket/round/assignment 下的真实 RECORDS 处理记录。组件通过 GET /api/finance/groups/{groupId}/fee-execution-projection?completionId=... 读取批准、已执行、真实剩余金额/数量、factVersion、终态及逐行 ticket/stock owner事实。浏览器只校验和展示投影，不自行推算权威余额；404、409、503或不完整投影会清空旧值并关闭提交。

未知结果仅查询原 UUID；404 只进入“未观察到”，重新取得完整权威投影后，用户显式操作才以冻结的同 UUID 和同参数继续。保留项同时绑定 tenant、actor、identity generation/attempt、ticket、round、assignment；卸载、身份或上下文变化后的迟到回调不能更新新组件。PART只展示stock真实USE owner事实，不由本页面提交。
