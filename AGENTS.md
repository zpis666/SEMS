# AGENTS.md — 共享快递袋系统（Shared Express Mailer System）

## 项目性质
本科毕业设计（广州软件学院 · 网络空间安全专业 · 曾攀 2240241121，指导教师杨宇夏）：设计并实现一个共享快递袋循环管理系统，覆盖「取—用—还—洗—调」闭环。所有交付物（文档、界面、注释）使用中文。

系统英文名 **Shared Express Mailer System**（缩写 SEMS）：开发阶段的工程目录、代码仓库与代码制品统一使用该名称；论文与文档正文仍用中文名"共享快递袋系统"。

命名约定：后端根包 `com.sems`，Maven 坐标 `groupId=com.sems` / `artifactId=sems-backend`；前端工程名 `sems-frontend`。**袋码前缀 `SJB-` 是印在实物上的编码格式，与包名无关，不要"顺手"改掉**。

## 目录与两条铁律
- `文档/任务书/2240241121_曾攀 本科毕业设计任务书.doc` — 权威任务书（二进制 .doc）。纯文本提取版：`文档/任务书/_extracted.txt`。任何涉及范围、技术选型的决策先读它。
- `文档/` — 论文配套文档存放处：需求规格说明书、系统设计说明书、数据库设计说明书、测试报告、用户使用手册（五份均为硬性交付物）。过程稿与历史记录放 `文档/归档/`。
- `Shared Express Mailer System/` — **开发主目录**（代码、部署脚本、开发说明）。子目录按职责分类，禁止在根目录堆放零散文件。
- `正式提交/` — 最终提交给导师/学校的材料。

> **铁律一：`正式提交/` 只读。** 除非用户明确要求，**不得对 `正式提交/` 内的任何文件做任何修改**——包括重命名、移动、格式化、批量替换、编码或换行符转换。该目录在 `.gitattributes` 中被标记为 `-text`，Git 不会改写其中任何字节，但仍能检测出被改动。
>
> **铁律二：环境优先用本机现有的。** 本机已装好的工具直接使用，不为项目另行安装同类工具（详见"本地开发环境"）。

## 固定技术栈（任务书规定，不得替换或"优化"）
- B/S 架构、前后端分离、RESTful API；后端向前端返回统一 JSON 格式。
- 后端：Spring Boot + MyBatis-Plus + MySQL + Redis。
- 前端：Vue 3 + Vite + Element Plus + Pinia + Axios；ECharts 数据看板；响应式移动端适配。
- 认证：JWT；WebSocket 实现库存与订单状态实时推送。
- 部署目标：云服务器（Nginx 反向代理 + 后端服务 + MySQL + Redis）。

**版本基线**（2026-10-06 逐项核对最新稳定版，详见《系统设计说明书》2.4，写 POM / package.json 时以此为准，偏离须登记）：
后端 Spring Boot **3.5.x**（3.x 末版；**明确不用 4.x**——Spring Framework 7 / Security 7 破坏性变更会牵动设计书 7.1 与 9.x）、MyBatis-Plus 3.5.x、JDK 21、JJWT 0.13.x、ZXing 3.5.x；
前端 Vue 3.5+、Vite 8.x（需 Node ≥20.19）、Element Plus 2.14+、Pinia 4.x（需 Vue ≥3.5.11）、Axios 1.x、ECharts 6.x。

## 领域模型（系统核心，改动需谨慎）
- 快递袋全生命周期状态机：空闲 → 已分配 → 使用中 → 待消毒 → 空闲（正常循环四态）；保留维修中、报废、已遗失、调拨在途扩展态。标记归还仅推进订单，消毒合格自动入库。所有流转必须由后端校验，禁止非法跳变与重复领取；每次流转写流转日志。
- 角色（RBAC，三类）：普通用户（取用/归还、订单查询、个人中心）；站点管理员（出入库、库存盘点、消毒登记、维修报废）；平台管理员（用户/订单/快递袋/站点管理、数据统计）。
- 功能模块：用户管理、共享快递袋服务、共享站点管理、快递袋管理、快递订单管理、后台管理与数据统计。
- 快递袋需有编码与二维码（展示 + 扫码识别）。

## 硬性要求
- 并发：用 Redis 缓存与过期机制做库存并发扣减，防止超发/漏发。
- 安全（专业方向即网络空间安全，按计分需求对待，不是附加项）：JWT 认证、接口越权校验、参数校验与 SQL 注入防护、敏感数据脱敏、操作日志审计。
- API 变更必须同步维护接口文档（任务书明确要求）。
- 单元测试、接口测试、并发性能测试均需执行并产出《测试报告》。

## 里程碑（答辩 2027-05，中期检查 2027-01）
- 2026-09-27 开题报告与外文翻译；2026-10-25 需求/设计/数据库文档；2026-11-29 后端核心模块 + 单元测试；2026-12-27 前端与前后端联调；2027-01-10 集成/并发测试、部署上线、中期检查；2027-04-04 测试报告/使用手册/论文初稿；2027-04-18 定稿并提交全部代码与文档。

## 本地开发环境（本机既有工具，不再另行安装）
| 工具 | 本机现状 | 使用方式 |
| --- | --- | --- |
| JDK | **21.0.11**，`E:\tools-env\jdk-21.0.11+10`，`JAVA_HOME` 已指向它 | 构建与运行**一律以 `JAVA_HOME` 为准**。注意：PATH 上排第一的 `java` 是 Oracle Java 8，**不属于本项目**，直接执行 `java -version` 会误报为 1.8 |
| Node.js | 24.16.0，`E:\tools-env\nodejs-24.16.0`（含 npm 11） | 前端构建与开发服务器 |
| Git | 2.54.0，`E:\tools-env\git-2.54.0.windows.1` | 仓库已初始化，分支 `main`，提交身份 `zp <1931662166@qq.com>` |
| PowerShell | **7.6.6**（Microsoft Store 安装，命令名 `pwsh`） | **默认 shell**；命令行操作一律走 pwsh，不要回落 Windows PowerShell 5.1 或 Git Bash。用法注意见「环境注意」 |
| Docker | CLI 29.4.3 + Compose v5.1.3（Docker Desktop 已安装） | 本地 MySQL 8.0 与 Redis 7 用容器运行，见 `Shared Express Mailer System/deploy/docker/`；使用前需启动 Docker Desktop |
| Maven | **本机没有，也不需要装** | 后端使用 Maven Wrapper（`mvnw`），随工程提交 |
| Maven 依赖源 | 已配阿里云公共仓库：`C:\Users\Admin1\.m2\settings.xml` | 本机全局配置，**不在仓库内、不参与交付**；2026-10-06 实测 340 次下载全部走 `aliyunmaven` |
| npm 依赖源 | 已配 npmmirror：`Shared Express Mailer System/frontend/.npmrc` | **只作用于本工程**（随仓库提交）；本机全局 registry 未改动，其它 Node 项目不受影响 |
| MySQL / Redis | **本机没有** | 由 `deploy/docker/docker-compose.yml` 提供，镜像版本与云服务器一致；禁用第三方 Redis Windows 移植版 |
| WSL2 | 有 Ubuntu 发行版（已停止） | 不使用，避免维护两套工具链 |
| JMeter | 未安装 | 2027-01 并发测试阶段再装 |

## 环境注意
- **默认 shell 是 PowerShell 7（pwsh）**，不再默认 Git Bash。Windows 平台，路径与文件名含中文，shell 命令注意引号与编码。
  - pwsh 里跑 Maven Wrapper 必须写 `.\mvnw.cmd`：写 `.\mvnw` 会失败（PowerShell 认到的是同目录那个无扩展名的 bash 脚本，报「无法在管道中间运行文档」）。2026-10-07 实测 `.\mvnw.cmd -v` 正常（Maven 3.9.11 / JDK 21.0.11）。
  - 不要再调 `powershell.exe`——那是 Windows PowerShell 5.1，不属于本项目。
  - Git Bash 仍可用，但只在确实需要 POSIX 脚本时才用。注意其中 `export MSYS_NO_PATHCONV=1` 会让 `./mvnw` 报
    `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`（MSYS 路径改写被关掉所致，**不是 Maven 坏了**）；
    该 export 与 `./mvnw` 不要写进同一条命令，或提前 `unset`。
- 仓库根目录有 `.zcodeignore`（仅影响 ZCode 文件搜索，不等同于 .gitignore）；维护忽略规则时以 `.gitignore` 为准。
- `.gitattributes` 已统一换行策略：源码/文档入库为 LF；`*.cmd`/`*.bat` 为 CRLF；`正式提交/**` 为 `-text`（字节原样）。
- 新建批处理脚本后需确保为 CRLF 换行，否则 cmd.exe 可能解析异常。
- **后端工程生成后**，把 `.mvn/wrapper/maven-wrapper.properties` 里的 `distributionUrl` 换成阿里云地址
  `https://maven.aliyun.com/repository/public/org/apache/maven/apache-maven/{版本}/apache-maven-{版本}-bin.zip`。
  该地址 2026-10-06 实测可用（3.9.9 / 3.9.11 均返回 zip）。不改的话，首次 `mvnw` 会去 Apache 官方站下 Maven 本体，国内很慢；
  `settings.xml` 的镜像只作用于依赖，管不到 Wrapper 下载 Maven 本体这一步。
- 写 XML 时注意：**注释里不能出现连续两个减号**，否则 Maven 直接拒绝加载 `settings.xml`（分隔线请用等号）。

<!-- aoci:begin -->
## AOCI 仓库认知

AOCI 为本仓库维护一个稳定、可版本化、可增量更新的仓库级认知层，供模型跨任务复用对系统的理解。

`aoci.txt` 是面向模型的结构化认知索引。它以每个受管理文件、数据库表或其他受管理对象一条独立 Entry 的方式，用符号标签与 F/R/A/S 语义表达对象的核心职责、重要关系、对外契约，以及理解或修改系统时必须知道的非显然约束和设计决策。

Header、目录段和全部 Entry 共同组成完整仓库索引，可以覆盖前端、后端、配置、数据库结构及其他受管理内容。受管理内容发生变化时，通常只需维护受影响的认知条目，不需要重新生成整个索引。

AOCI 提供系统架构、对象职责、重要关系、对外契约和关键约束的高密度视图。

### 工作原理

AOCI 采用“模型生成、模型读取”的认知闭环。

Header、Entry 和 Curation 语义的创作只按当前机器签发的 Plan 与实时 Guide 执行；由 Host 模型基于当前绑定证据独立完成。

Entry 的语义必须来自模型对真实证据的理解。不得仅依据路径、文件名、扩展名、AST、符号列表、依赖扫描、正则、固定模板或规则引擎推导、预填、拼接或改写索引语义。

对 Fresh Bootstrap，只按当前机器签发的 Plan 和实时 Guide 执行。当它们要求创作时，Host 模型创作 Root、Meta、Tag 和 F/R/A/S，提供 authoring-run 声明，并把它绑定到 Plan、Evidence 与完整 Candidate。不得要求 AOCI 填写 `origin=host_model`、制造 Receipt 或把程序生成的 Framework 当作语义。本文件不自行重建 Onboarding 流程。内部批次不是用户决策；只有遇到既有批准边界或真实的安全、漂移、CAS、Recovery 条件才停止。

### 最小使用入口

- `aoci_rules`：取得当前AOCI版本的会话运行合同。
- `aoci_overview`：建立或恢复本仓库的完整认知。
- `aoci_maintain`：受管理对象达到最终稳定状态后检查认知是否需要维护。
- `aoci_update_entry`：提交与当前证据和源码摘要绑定的完整语义更新批次。
- `aoci_report`：仅当当前布局和工具状态支持时，在证据不足、无法可靠生成语义时登记待办，不猜写。

其他MCP工具、CLI命令、参数和专项流程，以当前工具说明、Guide和 `--help` 返回内容为准，不在本文件中重复完整手册。

本区块只规定仓库接入、认知使用和收尾原则。`aoci_rules` 承载当前会话合同，Guide实时输出承载当前Plan的执行顺序与停点，工具Schema、Spec和Validator承载机器结构与判据；Prompt、Description、README和静态文档不能覆盖这些机器事实。

### 建立、生成和恢复认知

1. 每个新的 Agent Run 开始时，应先判断：

   - 本仓库是否已经存在可用的完整AOCI索引；
   - 当前上下文中是否已有与本仓库根、当前索引版本和当前AOCI服务相匹配，并且模型仍可可靠使用的完整仓库认知。

2. 仓库已经存在可用的完整索引，但当前Run没有可靠完整认知时，先调用 `aoci_rules`，再调用 `aoci_overview`。

   完整认知仍可靠时直接复用。局部不确定本身不要求机械重读系统全貌。

   本Run从已知Host上下文压缩恢复时（包括宿主注入的压缩摘要），必须把此前模型认知视为不可靠。压缩handoff不得保留或摘要正式Whole-Index，也不得保留或摘要任何Overview Header、Entry、Chunk、Challenge或Attestation正文；只能保留安全续接所需的receipt身份、未完成write或Recovery状态，以及立即重载指令。复制进handoff的Whole-Index语义或receipt不能证明恢复后模型的当前认知可靠。若当前上下文已无法可靠保留运行合同，先调用 `aoci_rules`。继续业务任务前，使用 `refresh_reasons=["context_compaction"]` 和新的 `refresh_event_id` 调用普通完整Whole-Index `aoci_overview`（不设置 `check_only` 或设为false）；不得使用 `check_only` 或认知probe。原样跟随每个 `next_cursor` 直到 `completed=true`，确认交付，并且只基于新交付正文提交一次Attestation。完成这次新的完整传输后，即使Attestation为partial或fail也消费该generation，并按既有合同继续source-bound任务，不再自动调用第二次Overview。

   AOCI可以针对 `context_compaction`、项目 `cognition_refresh_threshold` 下的机器 `semantic_threshold` 或主要 `phase_transition` 提供checkpoint与认知状态事实。只需要这些紧凑事实时使用 `check_only=true`；这些事实只向Agent提供建议，不替模型决定是否需要系统全貌。

   Agent显式调用普通 `aoci_overview`（未设置 `check_only` 或为false）时，只要能形成一致的CognitionSet，AOCI必须完整交付请求scope。不得因为已有receipt、阈值未达到或没有待处理刷新原因而抑制正文。正式认知Dirty或Stale时仍交付正文，但必须标记不可靠。存在未决恢复或无法形成一致snapshot时失败关闭，不返回混合正文。

   普通Overview返回 `continuation_required=true` 时，必须原样提交 `next_cursor` 并自动继续到 `completed=true`。不得询问用户、开始业务任务或给出阶段性系统结论。Host截断、缺块、重复、乱序、cursor失败、Index变化或`chunk_tokens`变化时停止本次认知链。Attestation完成前不得用Memory、源码、Spec、`aoci.txt`、历史会话、scope、search或Entry读取修补或补充Whole-Index认知。Challenge ordinal是正式Entry序列中的1-based位置；Header内容、注释、空行、Section/Overview/Chunk Marker、Receipt与Metadata均不计数，Chunk Receipt ordinal使用同一序列。Attestation必须原样回绑本次Challenge发布的当前`index_sha256`、`entry_sequence_sha256`与`entry_count`；旧Index、旧Entry序列、旧数量或旧Attestation均无效。完整链结束后只正式提交一次既有模型认知Attestation；同一响应只允许一次不改变语义答案的JSON Schema或字段格式修正。对象、Tag或F不匹配即失败且认知吸收不确定，不得语义重试或旁路补答。首次认知失败时还不得执行Root/Meta、Migration、全局布局或其他未重新绑定的系统级决策。上下文压缩刷新若传输完整、认知身份不变、治理对齐且没有Recovery或第三方冲突，即使Attestation为partial或fail也消耗该refresh generation，并继续原任务，不再自动重读Overview。`system_mastery_percent`只自评系统框架——架构、职责、强关系、稳定外部契约以及高熵安全和维护约束——不表示完整实现或运行实况知识；机器索引覆盖率必须分开。默认只向用户输出由本次真实覆盖率、Challenge、块数、Token和掌握度生成的规定成功或失败一句话。Host截断时提示用户把 `overview_delivery.chunk_tokens` 设置为更小的合法值后重新开始，不得自动修改。

   加法认知等级必须与严格证明字段分开解释。`delivery_verified`表示已加载Index且Host交付已确认，但完整认知验证仍未完成；应表达为“已加载且交付已验证”，不得描述为“没有认知”或“没有理解系统”。`cognition_verified`要求Attestation通过（Challenge至少80%的ordinal完全正确且对象身份至多失手一处），`cognition_governed`还要求治理对齐。通用完整读取失败句只用于真实交付故障。

   当Overview响应包含可选`cognition-state/v2`投影时，必须分别解释各维度。其Level止于`model_cognition_usable`；`strict_attestation_verified`、`governance_aligned`与`current_system_cognition_reliable`都是独立状态，绝不参与该Level。ordinal、对象身份、Tag或核心F不匹配可以导致严格Attestation失败，而模型认知仍然可用；不得仅凭这种不匹配就宣称模型没有理解系统。只有`current_system_cognition_reliable=true`允许无保留地声称当前完整系统认知可靠。投影缺失时继续使用上述Legacy解释。

   普通的只读审计、分析、检查、不修改代码或不提交、不push，不自动等于严格零写入，也不改变上述认知有效性判断。Codex Memory和历史Skill只能辅助恢复经验、用户偏好与调查方向，不能替代与当前仓库根、索引摘要、AOCI服务身份和认知范围匹配的当前认知收据；项目AGENTS和当前AOCI身份在AOCI状态上优先于历史Memory。

   只有用户明确禁止Ledger、元数据、`.aoci`运行资产及任何文件写入时，才按严格零写入处理。若必要的认知建立与该边界冲突，必须报告冲突并请求用户裁决或建议使用隔离副本，不得静默以Memory替代当前仓库认知。

3. 仓库没有可用的完整索引，或当前只有最小骨架、Header不完整、Entries未完成、必要Curation尚未裁决时，如果需要建立正式完整AOCI索引，先取得 `aoci_rules`，然后进入当前AOCI Guide。由Guide依据仓库真实状态决定下一阶段并完成必要安全步骤。

   `aoci_maintain` 不替代索引建立流程。

   不在本文件中自行重建或硬编码完整索引生成状态机。

4. 在长程任务中，模型负责保留当前认知收据并正确使用刷新门禁：

   - Host报告上下文压缩或模型已知系统全貌丢失时，执行上述强制 `context_compaction` 重载规则；AOCI不能自行推断Host事件；
   - 进入真正的主要阶段时声明 `phase_transition`，不得把函数、测试运行或小步骤当作阶段；
   - 在有用的稳定检查点通过 `check_only=true` 取得机器语义计数；
   - 除已知压缩的强制重载外，由Agent判断当前任务是否需要再次显式获取指定scope或完整Overview；
   - 在维护和对齐完成前，保留AOCI报告的Dirty或Stale可靠性状态。

### 任务收尾与认知维护

5. 纯只读问答、分析、版本核验，或没有产生受AOCI管理对象变化的任务，不需要调用维护工具。当前AOCI版本是任意`aoci_overview` check_only或`aoci_maintain`响应里的`cognition_receipt.mcp_service_version`；二进制路径是项目`.mcp.json`里的`command`，CLI不必在PATH上。

6. 发生受AOCI管理对象变化时，待其达到本次任务的最终稳定状态后，只调用一次 `aoci_maintain`。不要在每次中间修改后逐文件维护。

7. 若维护结果返回真实语义候选，Host 模型必须基于每个候选绑定的对象和必要证据，独立创作完整标签与F/R/A/S更新。通过 `aoci_update_entry` 一次提交当前机器签发批次的完整候选集合，同时原样保留每项 `source_sha256`、`candidate_id` 与对应domain批次身份。`max_entries`只限制单次请求和原子事务，不限制logical plan、Whole-Index或Managed Scope。`remaining`非零时，在当前批次成功Apply后重新调用Maintain并从新preimage继续；绝不能为满足transport上限缩减Index覆盖或自行截取返回批次。

   没有足够证据且当前布局支持 `aoci_report` 时，使用它而不猜测、套用模板或为消除待办而生成缺乏证据的认知。

8. 必须遵守工具返回的结构化状态和安全边界：

   - `repair_required`：只修复明确命中的候选，再重新提交当前机器签发的完整批次；
   - `stopped`：结束当前写入尝试并检查 `failed_step`、错误、正式写入证据与Recovery。auto模式下，已证明零写入则记录closure并重新Plan；完整Intent和可证明postimage则Resume；策略要求Rollback且preimage可证明则精确恢复后重新Plan。只有证据不足、第三方正式字节冲突、需要审批或外部动作，或命中其他真实安全边界时，才停止整个用户任务；
   - 冲突、审批、人工裁决、权限和安全信号不得忽略；
   - 已经对齐后不得重复维护或重复写入；`refresh_ready_for_overview` 是checkpoint事实，由Agent决定是否为下一阶段请求普通完整Overview。

   维护完成后如果又修改了任何受管理对象，之前的维护结果失效，应在新的最终稳定状态重新完成收尾。

9. 用户只限制业务文件范围，但没有明确禁止仓库托管资产时，AOCI托管资产可以在收尾阶段为保持认知一致而更新，并应在审计和提交中与业务文件区分。

   用户明确禁止修改 `aoci.txt`、`.aoci`、元数据或任何额外文件时，以用户限制为准，不得写入，并如实报告剩余不一致。

### 专项流程

初始化、完整索引生成、Header生成、Entries生成、数据库结构索引、Curation、人工评审和故障恢复，只按当前AOCI Guide或工具在对应阶段返回的指令、命令和安全停点执行。

不预加载、不猜测，也不自行重建这些专项流程。平台调用方式、请求格式、批次上限、审批规则、索引格式细节和恢复步骤由对应Guide、工具说明、模型Prompt和CLI帮助按需提供。
<!-- aoci:end -->
