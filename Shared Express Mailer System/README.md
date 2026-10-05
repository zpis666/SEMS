# Shared Express Mailer System

共享快递袋循环管理系统 · 本科毕业设计开发主目录
（广州软件学院 · 网络空间安全 · 曾攀 2240241121 · 指导教师 杨宇夏）

覆盖「取 — 用 — 还 — 洗 — 调」全闭环的共享快递袋管理系统。

> **文档边界**：论文交付物（需求规格说明书、系统设计说明书、数据库设计说明书、测试报告、用户使用手册）与任务书在仓库根目录 [`文档/`](../文档/)；
> 本目录只放**开发产物**（代码、部署脚本、开发说明），不重复存放论文文档。

## 目录结构

| 目录 | 用途 | 计划创建时间 |
| --- | --- | --- |
| `backend/` | Spring Boot 3.3 后端工程（Maven Wrapper，无需本机安装 Maven） | 2026-11 |
| `frontend/` | Vue 3 + Vite 前端工程（Element Plus / Pinia / Axios / ECharts） | 2026-12 |
| `deploy/docker/` | 本地中间件：docker-compose（MySQL 8.0 + Redis 7） | 2026-10 |
| `deploy/sql/` | 建库建表脚本、初始化数据 | 2026-10 |
| `deploy/nginx/` | 生产环境 Nginx 反向代理与静态托管配置 | 2027-01 |
| `scripts/` | 环境启停、构建、备份脚本 | 2026-10 |

## 开发环境（本机既有工具优先，不额外安装）

| 工具 | 本机版本 / 路径 | 说明 |
| --- | --- | --- |
| JDK | 21.0.11 · `E:\tools-env\jdk-21.0.11+10` | `JAVA_HOME` 已指向它；**注意** PATH 上的 `java` 仍是 Oracle Java 8，构建以 `JAVA_HOME` 为准 |
| Node.js | 24.16.0 · `E:\tools-env\nodejs-24.16.0` | 含 npm 11.13.0、pnpm |
| Git | 2.54.0 · `E:\tools-env\git-2.54.0.windows.1` | — |
| Docker | 29.4.3 + Compose v5.1.3 | 本地 MySQL / Redis 以容器运行；需先启动 Docker Desktop |
| Maven | 未安装（无需安装） | 后端使用随工程提交的 Maven Wrapper（`mvnw` / `mvnw.cmd`） |
| MySQL / Redis | 未安装（无需安装） | 由 `deploy/docker/` 提供，版本与云服务器一致（MySQL 8.0 / Redis 7） |

## 技术栈（任务书固定，不得替换）

- 后端：Spring Boot + MyBatis-Plus + MySQL + Redis
- 前端：Vue 3 + Vite + Element Plus + Pinia + Axios + ECharts
- 认证：JWT；实时推送：WebSocket
- 部署：云服务器（Nginx 反向代理 + 后端服务 + MySQL + Redis）

## 里程碑

| 时间 | 内容 |
| --- | --- |
| 2026-09-27 | 开题报告与外文翻译 |
| 2026-10-25 | 需求规格说明书、系统设计说明书、数据库设计说明书 |
| 2026-11-29 | 后端核心模块 + 单元测试 |
| 2026-12-27 | 前端开发与前后端联调 |
| 2027-01-10 | 集成/并发测试、部署上线、中期检查 |
| 2027-04-04 | 测试报告、用户使用手册、论文初稿 |
| 2027-04-18 | 定稿并提交全部代码与文档 |

## 快速开始：本地中间件（MySQL 8.0 + Redis 7）

本机**不安装** MySQL / Redis，两者由 Docker 容器提供，镜像版本与云服务器保持一致。

```bat
scripts\db-up.cmd      启动（拉起 sems-mysql / sems-redis）
scripts\db-down.cmd    停止（保留数据卷）
scripts\db-reset.cmd   清空数据卷重建（会删除本地数据，需输入 yes 确认）
scripts\check-env.cmd  打印本机 JDK / Node / Git / Docker 版本，用于换机后自检
```

| 服务 | 地址 | 库与账号 |
| --- | --- | --- |
| MySQL 8.0 | `localhost:3306` | 库 `sems`，用户 `sems` / `root`，口令见 `deploy/docker/.env.example` |
| Redis 7 | `localhost:6379` | 已开启 `requirepass`，口令同上 |

- 建表脚本放 `deploy/sql/`，容器**首次**初始化时按文件名顺序自动导入；脚本更新后用 `db-reset.cmd` 重建。
- 前置条件：启动 Docker Desktop（本机已安装，守护进程需处于 Running）。

## 约定

- 所有交付物、界面文案、代码注释使用**中文**。
- API 变更必须同步维护接口文档。
- 子目录按职责分类归档，不在根目录堆放零散文件。
- 生产密钥（JWT 密钥、数据库/Redis 口令）一律走环境变量，禁止入库（见 `.gitignore`）。
