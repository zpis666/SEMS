# 数据库脚本目录（deploy/sql）

本目录存放建库建表 DDL、初始化数据与后续升级脚本，是"数据库设计说明书 → 实际数据库"之间的可执行落地物。

## 约定

- 文件按版本顺序命名，容器首次初始化时按**文件名升序**执行（V1 → V2 → …）：
  - `V1__init_schema.sql` —— 建表（随《数据库设计说明书》产出）
  - `V2__init_data.sql` —— 初始化数据（三类角色、预置平台管理员账号、示例站点）
  - `V3__xxx.sql` —— 后续变更
- 目标库为 `sems`（由容器环境变量 `MYSQL_DATABASE` 注入，见 `deploy/docker/.env.example`），字符集 utf8mb4 / 排序规则 utf8mb4_0900_ai_ci，与 `docker-compose.yml` 保持一致。
- 表结构以《数据库设计说明书》第 4 章为准；**如需变更，先改文档、再改脚本，并在修订历史登记**，不允许只改一边。
- 所有脚本必须可重复执行的语义要明确：建表统一用 `CREATE TABLE IF NOT EXISTS`，初始化数据统一用 `INSERT ... ON DUPLICATE KEY UPDATE` 或先判空。

## 怎么执行

| 场景 | 做法 |
| --- | --- |
| 全新环境（数据卷为空） | `deploy/docker/docker-compose.yml` 已把本目录挂载到容器 `/docker-entrypoint-initdb.d`，`scripts\db-up.cmd` 首次启动时自动导入 |
| 已有数据卷、脚本有更新 | 执行 `scripts\db-reset.cmd` 清空重建（**会删除本地全部数据**），或手工 `docker exec -i sems-mysql mysql -uroot -p sems < 脚本.sql` |
| 手工连库 | `docker exec -it sems-mysql mysql -uroot -p` |

## 初始化数据（V2__init_data.sql）

- 内容：三类角色（`USER` 普通用户、`STATION_ADMIN` 站点管理员、`PLATFORM_ADMIN` 平台管理员）；1 个预置平台管理员账号；2 个示例站点（校园东区/西区快递服务中心，含库存预警阈值）。
- 预置平台管理员：手机号 `13800000000`，昵称 `平台管理员`，初始口令 `Sems@2026`；`must_change_password=1`，**首次登录强制修改密码**，改密前不得开展业务。
- 可重复执行：全部 `INSERT ... ON DUPLICATE KEY UPDATE`（依赖 V1 唯一键）；重复执行会把预置数据恢复为上述初始值。
- 执行顺序：容器**首次**初始化时按文件名升序自动导入（`V1` → `V2`）；数据卷已存在时不会重跑，脚本变更请用 `scripts\db-reset.cmd` 重建（见上表）。

## 四态模型升级（V3__simplify_bag_status.sql）

已有数据库先停止后端并备份，再执行 V3；无需清空数据卷。新环境按 V1 → V2 → V3 初始化。
旧 INACTIVE、REUSABLE 合并到 IDLE；RETURNING 合并到 IN_USE，订单仍保留待回收确认。
已达复用上限的旧 INACTIVE/REUSABLE 转 TO_DISINFECT，后续登记报废。
历史流转日志保留原码。升级后清除 `stock:available:*` 和 `stock:sentinel:*`，重启后端按空闲袋数重建库存。
新袋建档自动入库；消毒合格自动回空闲；原激活和单独上架接口返回 4102。
