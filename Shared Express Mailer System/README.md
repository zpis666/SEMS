# Shared Express Mailer System

共享快递袋循环管理系统 · 本科毕业设计开发主目录
（广州软件学院 · 网络空间安全 · 曾攀 2240241121 · 指导教师 杨宇夏）

覆盖「取 — 用 — 还 — 洗 — 调」全闭环的共享快递袋管理系统。

**当前系统版本：SEMS v0.1.2**（前端视觉升级：青绿循环设计系统、品牌标识与认证页、管理端导航、运营看板及用户端 H5 重构；业务接口、角色权限与状态机不变）

发布说明：[v0.1.2](docs/releases/v0.1.2.md) · [v0.1.1](docs/releases/v0.1.1.md)

> **文档边界**：论文交付物（需求规格说明书、系统设计说明书、数据库设计说明书、测试报告、用户使用手册）与任务书在仓库根目录 [`文档/`](../文档/)；
> 本目录只放**开发产物**（代码、部署脚本、开发说明），不重复存放论文文档。

## 目录结构

| 目录 | 用途 | 计划创建时间 |
| --- | --- | --- |
| `backend/` | Spring Boot 3.5.x 后端工程（Maven Wrapper，无需本机安装 Maven；版本基线以《系统设计说明书》2.4 为准） | 2026-10 |
| `frontend/` | Vue 3 + Vite 前端工程（Element Plus / Pinia / Axios / ECharts） | 2026-12 |
| `deploy/docker/` | 本地中间件：docker-compose（MySQL 8.0 + Redis 7） | 2026-10 |
| `deploy/sql/` | 建库建表脚本、初始化数据 | 2026-10 |
| `deploy/nginx/` | 生产环境 Nginx 反向代理与静态托管配置 | 2027-01 |
| `scripts/` | 环境启停、构建、备份脚本 | 2026-10 |

## v0.1.2 已实现范围

| 层 | 内容 | 落点 |
| --- | --- | --- |
| 后端 | 21 张表的实体与 Mapper；统一响应/错误码/全局异常；JWT 认证与两级吊销（含双密钥轮换窗口）、`@RequireRole` 等级制鉴权、数据权限校验；`@AuditLog` 审计切面（异步落库 + 队列饱和降级 + 越权/状态机拒绝的关键事件投递）；Redis 库存 Lua 原子扣减与 R0–R4 五条回补路径（含熔断降级）；21 条转移的袋状态机（唯一流转入口）；M1–M6 全部 **70 个端点**；WebSocket 首帧鉴权推送；6 个定时任务 | `backend/` |
| 前端 | 设计令牌与 Element Plus 变量全量映射；Axios 封装（token/幂等头/401 跳转/5102·5103 退避重试并复用幂等 ID）；路由与角色守卫；用户端（H5 优先）、站点端、平台端三套界面（**43 个页面**）；ECharts 看板；扫码（BarcodeDetector 降级 jsQR） | `frontend/` |

四态正常循环（空闲、已分配、使用中、待消毒）与扩展态口径自 v0.1.1 起保持稳定。v0.1.2 仅调整前端视觉与文档，未改动后端接口、状态机与权限逻辑；v0.1.1 的并发与 API 回归结果仍见 `文档/测试报告.md`，本次发布说明见 `docs/releases/v0.1.2.md`。

**两条命令即可跑起来**：

```bat
scripts\db-up.cmd                              :: 起 MySQL 8.0 + Redis 7（首次自动导入建表与种子）
cd "Shared Express Mailer System\backend"  &&  mvnw.cmd spring-boot:run    :: 后端 8090（pom 已默认 dev）
cd "Shared Express Mailer System\frontend" &&  npm install && npm run dev   :: 前端 5173
```

### 本地演示账号

当前本地开发库提供以下四个演示账号，初始/重置口令统一为 `Sems@2026`：

| 角色 | 手机号 | 昵称 | 绑定站点 | 初始/重置口令 |
| --- | --- | --- | --- | --- |
| 平台管理员 | `13800000000` | 平台管理员 | — | `Sems@2026` |
| 站点管理员 | `13900000001` | 东区站点管理员 | 校园东区快递服务中心 | `Sems@2026` |
| 普通用户 | `13900000002` | 取袋同学 | — | `Sems@2026` |
| 站点管理员 | `13900000003` | 西区站点管理员 | 校园西区快递服务中心 | `Sems@2026` |

四个账号均已重置为初始口令，首次登录后必须修改密码。初始化 SQL 只自动创建平台管理员；其余三个是本地联调演示账号，执行 `scripts\db-reset.cmd` 清空数据后需要重新创建。

**接口清单与业务规则**以《系统设计说明书》第 5 章为唯一出处；前端调用与后端实现均按此对齐，不另立口径。
实现阶段对设计书的回写（袋码校验位算法、业务单号生成规则、批量接口信封形态、JWT 轮换配置键）见该文档 V1.9 修订历史。

## 开发环境（本机既有工具优先，不额外安装）

| 工具 | 本机版本 / 路径 | 说明 |
| --- | --- | --- |
| JDK | 21.0.11 · `E:\tools-env\jdk-21.0.11+10` | `JAVA_HOME` 已指向它；**注意** PATH 上的 `java` 仍是 Oracle Java 8，构建以 `JAVA_HOME` 为准 |
| Node.js | 24.16.0 · `E:\tools-env\nodejs-24.16.0` | 含 npm 11.13.0、pnpm |
| Git | 2.54.0 · `E:\tools-env\git-2.54.0.windows.1` | — |
| Docker | 29.4.3 + Compose v5.1.3 | 本地 MySQL / Redis 以容器运行；需先启动 Docker Desktop |
| Maven | 未安装（无需安装） | 后端使用随工程提交的 Maven Wrapper（`mvnw` / `mvnw.cmd`） |
| Maven 依赖源 | 阿里云公共仓库（`~/.m2/settings.xml`，本机全局） | 首次构建免于直连中央仓库；该文件不在仓库内 |
| npm 依赖源 | npmmirror（`frontend/.npmrc`，随工程提交） | 只影响本工程，全局 registry 未改动 |
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

## 常见问题排查

### Navicat 12.0.11 连接本地 MySQL

本地 Compose 使用 `mysql_native_password`，兼容 Navicat 12.0.11。
MySQL 仍为 8.0，已有数据卷不会重建。已有账号不会因修改启动参数自动切换认证，
在工程目录用 PowerShell 7 执行一次：

```powershell
pwsh -File .\scripts\db-navicat-compat.ps1
docker compose -f .\deploy\docker\docker-compose.yml up -d --no-deps mysql
```

脚本从运行中容器读取本地配置，将 `root` 和 `MYSQL_USER` 的现有账号切换为兼容认证，
保留容器配置中的口令及原有权限。若曾单独修改数据库账号口令，应先同步本地配置；
容器配置与实际口令不一致时不要执行此脚本。

Navicat 连接设置：主机 `127.0.0.1`、端口 `3306`（自定义端口以 `.env` 为准），
用户名 `sems`、默认本地口令 `sems_dev_pwd`、数据库 `sems`。
需要管理数据库账号时可使用 `root`，默认本地口令 `root_dev_pwd`。
这些是数据库账号，与 Web 页面登录账号不同。

此兼容项仅用于本地开发，生产环境继续使用 `caching_sha2_password`。
恢复时先运行 `pwsh -File .\scripts\db-navicat-compat.ps1 -AuthenticationPlugin caching_sha2_password`，
再移除 Compose 的 `--default-authentication-plugin=mysql_native_password` 并重新启动 MySQL 容器。
不要为此运行 `db-reset.cmd`，它会删除数据。

| 现象 | 处理办法 |
| --- | --- |
| `db-up.cmd` 提示端口被占用（3306/6379） | 端口可能被其它程序占用（本机常见：phpstudy、其它 MySQL/Redis 实例）：先停止占用程序，或修改 `deploy/docker/.env` 中的 `MYSQL_PORT` / `REDIS_PORT` 为其它空闲端口后重试（若尚无 .env，请先从 .env.example 复制为 .env） |
| docker 报 `port is already allocated` | 同上；必要时先执行 `scripts\db-down.cmd` 释放端口，再重新 `db-up.cmd` |
| 容器已启动，但脚本没有导入 | `deploy/sql/` 仅在数据卷为空的**首次**初始化时导入；脚本有更新请用 `scripts\db-reset.cmd` 清空重建（**会删除本地数据**） |
| Docker Desktop 未运行 | 先启动 Docker Desktop，等状态变为 Running 后再执行 `db-up.cmd` |

## 约定

- 所有交付物、界面文案、代码注释使用**中文**。
- API 变更必须同步维护接口文档。
- 子目录按职责分类归档，不在根目录堆放零散文件。
- 生产密钥（JWT 密钥、数据库/Redis 口令）一律走环境变量，禁止入库（见 `.gitignore`）。
