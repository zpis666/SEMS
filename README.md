# SEMS（共享快递袋系统）

Shared Express Mailer System（SEMS）是面向校园、社区等快递集散场景的共享快递袋循环管理系统，覆盖「取、用、还、洗、调」业务闭环。本仓库保存本科毕业设计的源代码、部署脚本与配套文档。

当前工程版本：**v0.1.1**。

## 功能与角色

- 普通用户：扫码取袋、寄件、标记归还、订单查询和个人资料维护。
- 站点管理员：实物出入库、库存盘点、回收确认、消毒登记、维修报废和调拨出入库。
- 平台管理员：用户、订单、快递袋和站点管理，调拨管理、统计看板与审计查询。
- 快递袋提供 `SJB-` 编码、二维码和流转追溯；库存与订单状态通过 WebSocket 实时推送。

正常袋循环：空闲 → 已分配 → 使用中 → 待消毒 → 空闲；另保留维修中、报废、已遗失与调拨在途状态。标记归还只推进订单，已使用快递袋经站点确认回收后进入消毒流程，消毒合格后恢复可用库存。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | JDK 21、Spring Boot 3.5.x、MyBatis-Plus、MySQL 8.0、Redis 7 |
| 前端 | Vue 3、Vite 8、Element Plus、Pinia、Axios、ECharts |
| 认证与通信 | JWT、RESTful API、WebSocket |
| 部署 | Docker Compose 本地中间件、Nginx 生产反向代理 |

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `Shared Express Mailer System/backend/` | 后端代码、Maven Wrapper 和单元测试 |
| `Shared Express Mailer System/frontend/` | 用户端、站点端和平台端前端 |
| `Shared Express Mailer System/deploy/` | Docker、SQL 和 Nginx 配置 |
| `Shared Express Mailer System/scripts/` | 环境管理与本地开发辅助脚本 |
| `文档/` | 需求、设计、数据库与测试文档 |
| `aoci*.txt`、`.aoci/` | 仓库认知索引与正式治理资产 |

本地 `正式提交/` 为只读材料目录，目前没有纳入 Git；本机配置、运行日志和临时凭据也不在上传范围内。

## 本地启动

使用 PowerShell 7，先启动 Docker Desktop，并确认 `JAVA_HOME` 指向 JDK 21。前端需要 Node.js 20.19 及以上的 20.x，或 22.12 及以上版本。

在仓库根目录启动 MySQL 和 Redis：

```powershell
Set-Location 'Shared Express Mailer System'
.\scripts\db-up.cmd
```

另开终端，在后端目录启动服务：

```powershell
Set-Location 'Shared Express Mailer System/backend'
.\mvnw.cmd spring-boot:run
```

另开终端，在前端目录安装依赖并启动：

```powershell
Set-Location 'Shared Express Mailer System/frontend'
npm ci
npm run dev
```

默认访问地址：前端 `http://localhost:5173`，后端存活探针 `http://localhost:8090/api/health`。演示账号、数据库兼容配置和详细环境说明见 [工程 README](<Shared Express Mailer System/README.md>)。仓库中的开发口令仅供本地演示；生产环境通过环境变量提供凭据。

## 文档与验证

- [需求规格说明书](文档/需求规格说明书.md)
- [系统设计说明书](文档/系统设计说明书.md)
- [数据库设计说明书](文档/数据库设计说明书.md)
- [测试报告](文档/测试报告.md)与[机器可读测试附件](文档/测试附件/v0.1.1/)
- [v0.1.1 发布说明](<Shared Express Mailer System/docs/releases/v0.1.1.md>)

现有测试报告记录 **2026-10-07 的 v0.1.1 阶段验证**，其中并发回归检查竞争正确性，不代表完整性能压测。完整集成、性能与部署验证，以及用户使用手册，仍按毕业设计计划推进。
