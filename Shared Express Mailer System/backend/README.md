# sems-backend — 共享快递袋系统后端

共享快递袋系统（Shared Express Mailer System，SEMS）后端服务。
技术栈、版本基线与依赖坐标的**唯一出处**是《系统设计说明书》2.4；工程结构与分层见 2.9；配置键见 11.2。

## 一、构建与运行

本机**没有安装 Maven，也不需要安装**——一律使用工程内的 Maven Wrapper。

```bash
cd "Shared Express Mailer System/backend"

# 1) 先确认 JAVA_HOME 指向 JDK 21（本机 PATH 上排第一的 java 是 Oracle Java 8，不属于本项目）
echo "$JAVA_HOME"          # 应为 E:\tools-env\jdk-21.0.11+10

# 2) 编译 / 跑测试 / 打包
./mvnw -q clean test       # 单元测试（不依赖 MySQL/Redis 的部分）
./mvnw -q clean package    # 产出 target/sems-backend-0.1.0.jar
./mvnw -q spring-boot:run  # 本地启动（需先起中间件，见下）；pom 已把 run 目标默认设为 dev profile
```

> Windows 上如果 `./mvnw` 不可用，直接用 `mvnw.cmd`。
> 首次执行会按 `.mvn/wrapper/maven-wrapper.properties` 的 `distributionUrl` 从**阿里云**下载 Maven 本体（约 9MB），
> 之后不再重复下载；依赖同样走阿里云（见本机 `~/.m2/settings.xml`）。
>
> **关于 profile**：`application.yml` **刻意不设默认 profile**——否则打包出的 jar 也会默认 dev，
> 生产忘记切 profile 就会拿 dev 里提交在库的固定 JWT 密钥对外服务（公开密钥可伪造任意令牌）。
> 因此 `java -jar` 启动必须显式给 `spring.profiles.active=prod` 与全部环境变量，否则因缺数据源与密钥**直接启动失败**（fail-closed）。
> 本地开发不受影响：`mvnw spring-boot:run` 已由 pom 的插件配置默认激活 dev。
>
> **另一个坑**：在 Git Bash 里 `export MSYS_NO_PATHCONV=1` 之后再跑 `./mvnw`，会报
> `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`——
> 那是该变量关掉了路径改写、让 mvnw 传给 java 的 classpath 变成 MSYS 路径所致，**不是 Maven 装坏了**。
> 它与 `docker exec` / `taskkill` 一起用时才需要，请分开执行。

**服务端口**：生产与 Nginx 反向代理按 `8080` 对接（设计书 11.1）；**本地开发默认跑 `8090`**——
本机 8080 常被 Burp Suite 的代理监听占用，写死在 8080 会每次启动都撞端口（改法见 `application-dev.yml`）。

## 二、本地中间件

MySQL 8.0 与 Redis 7 由 `../deploy/docker/docker-compose.yml` 提供，镜像版本与云服务器一致。

```bash
../../scripts/db-up.cmd      # 启动（首次会自动导入 deploy/sql/V1__init_schema.sql 与 V2__init_data.sql）
../../scripts/db-reset.cmd   # 清空重建（改了 sql 脚本后用这个，普通重启不会重跑初始化）
../../scripts/db-down.cmd    # 停止
```

- 默认端口：MySQL `3306`、Redis `6379`。若被占用（本机装过 phpstudy 之类的常见），
  脚本会给出提示，改 `deploy/docker/.env` 的 `MYSQL_PORT` / `REDIS_PORT` 即可。
- 初始化后会有一个**预置平台管理员**：手机号 `13800000000`，初始口令 `Sems@2026`，
  `must_change_password=1`——首次登录必须改密。

## 三、配置

| 文件 | 用途 |
| --- | --- |
| `application.yml` | 公共配置 + 业务参数默认值（默认值必须与设计书 3.5 口径表逐字一致） |
| `application-dev.yml` | 本地开发：连本机容器，口令默认值与 `deploy/docker/.env.example` 一致 |
| `application-prod.yml` | 生产：**不含明文密钥**，数据库/Redis 口令与 JWT 密钥全部走环境变量 |

## 四、已实现范围（SEMS v0.1.0）

M1–M6 全部接口、21 条转移的袋状态机、Redis 库存原子扣减与 R0–R4 回补、WebSocket 推送、5 个定时任务均已实现。

```
com.sems
├── SemsApplication          启动类（@MapperScan 扫描 com.sems.module 下带 @Mapper 的接口）
├── common/                  Result PageResult ErrorCode BizException GlobalExceptionHandler
│   │                        HealthController BatchResult（逐袋批量接口统一信封）SemsVersion Constants
│   └── util/                MaskUtils（脱敏/HMAC）BagCodeUtils（袋码与校验位）BizNoUtils JsonUtils
├── config/                  MyBatis-Plus（乐观锁+分页）Redis SemsProperties
│                            WebMvc（拦截器注册/CORS）WebSocket OpenApi Async PasswordEncoder
├── security/                LoginUser AuthContext JwtService RequireRole AuthInterceptor AuthzInterceptor CurrentUser
├── infra/                   RedisKeys StockService（Lua 扣减与熔断）IdempotentService RateLimitService CounterService
├── aspect/                  @AuditLog 注解与审计切面（异步落库 + 队列饱和降级）
├── push/                    PushService WsSessionManager SemsWsHandler WsMessage
├── job/                     超时订单 / 回收与调拨超时 / 库存对账(R4) / 开单计数对账 / StartupRunner(R3)
└── module/
    ├── statemachine/        BagStatus BagEvent OrderStatus TransitionRegistry BagTransitionService 等
    └── {auth,user,bag,station,order,stats,audit}/
                             各模块按 controller/service/mapper/entity/dto 分层（见设计书 2.9）
```

四条已固化的约定：

1. **错误码 → HTTP 状态映射**写死在 `ErrorCode` 枚举里，并由
   `src/test/java/com/sems/common/ErrorCodeMappingTest.java` 对照设计书 6.4/6.6 断言。改映射必须同时改文档与该测试。
2. **状态机的 21 条转移**由 `TransitionRegistry` 静态注册，`TransitionRegistryTest` 逐条固化断言
   （含 220 种 (状态,事件) 组合中 26 条合法、其余必须被拒）。改转移表必须同时改该测试。
3. **逐袋批量接口统一返回 `BatchResult` 信封**：`data = { total, success, failed, items[] }`，
   元素固定含 `bagCode/success/code/message`（成功项 `code=0`，不能留 null——`non_null` 过滤会让字段整个消失）。
   见设计书 6.2；前端用 `utils/batch.js` 的 `extractBatch()` 取值，**不写兼容多形态的猜测代码**。
4. **Mapper 接口必须标 `@Mapper`**（设计书写的通配符扫描 MyBatis 不支持，见 `SemsApplication` 注释）；
   `mybatis-plus.type-aliases-package` **只列实体包**，写大了会因 DTO 同名（如两个 `UpdateStationDto`）导致
   `SqlSessionFactory` 建不出来、应用起不来（已在 `application.yml` 注释里记下这个坑）。

## 五、测试与覆盖率门禁

```bash
./mvnw -o test      # 单元测试（不依赖 MySQL/Redis），当前 19 项全绿
./mvnw verify       # 覆盖率门禁：整体行覆盖 ≥70%、状态机分支覆盖 100%（未达标即构建失败）
```

**`verify` 目前是红的，这是如实状态、不是故障。** 现状：

| 门禁项 | 目标 | 当前 | 差距原因 |
| --- | --- | --- | --- |
| 整体行覆盖 | ≥70% | **2%** | 实现已全部完成（228 个源文件），但单元测试只覆盖了状态机转移表、错误码映射、脱敏与编码工具三类；Service 层的用例尚未补 |
| 状态机分支覆盖 | 100% | 75%–91%（`BagTransitionService` 为 0%） | 转移注册表已 100% 被断言覆盖；流转服务本身的守卫分支需要 mock 编排才能覆盖 |

补充测试是 2026-11「后端核心模块 + 单元测试」里程碑的工作项，**不是本版遗留的缺陷**。
按设计书 12.2，`verify` 就是单元测试行的通过标准，所以这里**保留红灯而不放宽阈值**——
把阈值调低去换绿色构建，等于把门禁变成装饰。

JaCoCo 报告在 `target/site/jacoco/index.html`；数据文件写在系统临时目录（原因见 pom 里 jacoco 插件的注释）。

> **一个已经踩过的坑**：JaCoCo 的参数名是 `destFile`（大写 F）。写成小写 `destfile` 时 Maven 会把它当
> **未知参数静默忽略**（只在 `-X` 日志里留一行 warning），结果是 prepare-agent 退回默认路径、
> `report`/`check` 双双「Skipping JaCoCo execution due to missing execution data file」，
> **构建一路 SUCCESS 而门禁完全没在把关**。发现它靠的是看 `./mvnw -o -X test` 的日志，不是看构建结果。

## 六、下一步

按里程碑（2027-01 集成/并发测试）后续要做的是：`@Tag("integration")` 的真实 MySQL/Redis 集成测试、
JMeter 并发取袋压测（500 并发 100 只袋，成功数须恰为 100）与对账脚本、以及接口测试用例落到 Apifox。
