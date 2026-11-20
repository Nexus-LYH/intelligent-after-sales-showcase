# 智能售后与服务协作平台

面向设备售后服务的模拟企业项目。客户在门户查看授权设备、提交报修并确认处理结果。服务商通过员工工作台受理、派工、处理和关闭工单，并协作处理备件、库存、报价与通知。

## 技术栈

- 前端使用 Vue 3、TypeScript、Vite、Element Plus，以及 HeroUI v3 样式与 Tailwind CSS v4。
- 后端使用 Java 21、Spring Boot、Maven、MyBatis 和 MySQL。
- `apps/customer` 是客户门户，`apps/staff` 是员工工作台。
- `services` 包含网关、身份、设备、工单、审批、库存、费用和通知等模块，`ops` 包含构建检查和本地运维脚本。

## 本地开发

各前端目录可用 `npm ci` 安装依赖，`npm run dev` 启动开发服务，`npm run build` 执行类型检查和生产构建。员工端可运行 `npm test`。登录与业务功能需要可用的后端与 MySQL 环境。后端模块安装和运行说明见 [服务文档](services/README.md)，Windows 本地配置见 [本地运行文档](ops/local/WINDOWS-LOCAL.md)。示例中的 `C:/ias-local` 是演示路径，按本机安装位置和隔离环境调整。

这是源码展示仓库。数据库、账号凭据、运行产物和本地验收记录由部署环境自行管理。
