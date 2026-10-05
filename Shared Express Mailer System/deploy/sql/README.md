# 数据库脚本目录（deploy/sql）

本目录存放建库建表 DDL、初始化数据与后续升级脚本，是"数据库设计说明书 → 实际数据库"之间的可执行落地物。

## 约定

- 文件按版本顺序命名，容器首次初始化时按**文件名升序**执行：
  - `V1__init_schema.sql` —— 建表（随《数据库设计说明书》产出）
  - `V2__init_data.sql` —— 初始化数据（角色、权限点、示例站点与账号）
  - `V3__xxx.sql` —— 后续变更
- 表结构以《数据库设计说明书》为准，**文档与脚本必须同步维护**，不允许只改一边。
- 所有脚本必须可重复执行的语义要明确：建表统一用 `CREATE TABLE IF NOT EXISTS`，初始化数据统一用 `INSERT ... ON DUPLICATE KEY UPDATE` 或先判空。

## 怎么执行

| 场景 | 做法 |
| --- | --- |
| 全新环境（数据卷为空） | `deploy/docker/docker-compose.yml` 已把本目录挂载到容器 `/docker-entrypoint-initdb.d`，`scripts\db-up.cmd` 首次启动时自动导入 |
| 已有数据卷、脚本有更新 | 执行 `scripts\db-reset.cmd` 清空重建（**会删除本地全部数据**），或手工 `docker exec -i sems-mysql mysql -uroot -p sems < 脚本.sql` |
| 手工连库 | `docker exec -it sems-mysql mysql -uroot -p` |
