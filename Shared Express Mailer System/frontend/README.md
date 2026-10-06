# sems-frontend — 共享快递袋系统前端

共享快递袋系统（Shared Express Mailer System，SEMS）前端工程，对应后端 **SEMS v0.1.0**。
页面清单与视觉规范的**唯一出处**是《系统设计说明书》10.2 与 10.5；接口清单见 5.x；
校验规则与错误提示约定的唯一出处是 6.6 与 10.4。

## 一、构建与运行

本机 Node.js **24.16.0**（`E:\tools-env\nodejs-24.16.0`，含 npm 11）。依赖源已在本工程 `.npmrc` 指向 npmmirror，
**只影响本目录**，不改本机全局 registry。

```bash
cd "Shared Express Mailer System/frontend"
npm install          # 首次
npm run dev          # 开发服务器，默认 http://localhost:5173
npm run build        # 产出 dist/（部署时覆盖 Nginx 静态目录，见设计书 11.3）
npm run preview      # 本地预览构建产物
```

**后端联调**：`vite.config.js` 已把 `/api` 与 `/ws` 代理到 `http://127.0.0.1:8090`
（生产按设计书 11.1 走 Nginx 反代 8080；本机 8080 被 Burp Suite 占用，后端 dev 配置已改 8090）。
业务代码里不出现任何后端地址——开发期靠代理，生产期靠 Nginx，与 9.4「生产同域部署、CORS 默认关闭」一致。

**演示账号**：手机号 `13800000000` / 初始口令 `Sems@2026`（V2 种子预置的平台管理员，首次登录强制改密）。

## 二、目录结构（设计书 10.1）

```
src/
├── api/            按模块封装的接口函数（auth/user/bag/order/station/allocation/stats）
├── components/     公共组件：StatusTag BagTimeline BagCodeInput BatchResultDialog
│                   PageCard PageSkeleton EmptyState DataPager StatCard
├── constants/      枚举与文案映射（袋 11 态 / 订单 6 态 / 调拨 / 盘点 / 告警类型…）
├── layouts/        UserLayout（用户端 H5）AdminLayout（站点端与平台端共用）
├── router/         路由表 + 全局守卫（未登录跳登录、按 role 过滤、强制改密拉回）
├── stores/         Pinia：user（token/角色/续签调度）websocket（连接/重连/订阅）
├── styles/         tokens.css（设计令牌，全站唯一出处）
│                   element-override.css（Element Plus 变量全量映射）
│                   index.css（全局基线与工具类）
├── utils/          request（token/幂等头/错误分流/退避重试）validators batch scanner
└── views/          common / user / station / platform 四组页面
```

## 三、四条必须遵守的约定

1. **颜色一律走 CSS 变量**（`var(--sems-*)`），页面与组件里**禁止出现十六进制色值**——
   唯一例外是 `styles/tokens.css` 与 `styles/element-override.css` 这两个令牌定义文件（设计书 10.5 禁止事项 1）。
2. **状态展示一律用 `<StatusTag>`**，不允许仅靠颜色区分状态；绿色系标签自带对勾图标（色觉障碍可辨）。
3. **批量结果一律用 `utils/batch.js` 的 `extractBatch()`** 取值。
   后端七个逐袋批量接口统一返回 `data = { total, success, failed, items[] }`（设计书 6.2），
   **不要写 `results / records / details / 裸数组` 的兼容猜测**——猜形态会让契约漂移无从发现，
   这个坑已经踩过一次（回收确认结果弹窗因取错键名，长期静默显示「成功 0 / 失败 0」）。
4. **校验规则与后端同源**：`utils/validators.js` 的正则与后端 `com.sems.common.Constants` 逐字一致，
   改一处必须改两处。前端校验只是体验性拦截，**后端才是权威**（设计书 10.4）。

## 四、会话与实时推送

- token 以内存为主、`sessionStorage` 兜底（标签页级，关闭即清除）；剩余 **<30 分钟**由定时器触发静默续签（设计书 7.1）。
- WebSocket 端点 `/ws`，**首帧鉴权**（token 不进 URL，避免被 Nginx 访问日志记录）；
  断线按 `1s/2s/4s/…/30s` 指数退避重连，回前台立即重连并重新拉取当前页数据兜底（7.3）。
- **续签会拉黑旧 jti，WebSocket 会在下次心跳断开，这是预期行为**——
  因此重连前必须先刷新 token 再建连（`stores/websocket.js` 的 `ensureConnected()` 已按此实现）。

## 五、响应式与三态

- 三块布局按角色分域：`/m/**` 用户端（<768px 单列卡片流 + 底部 Tab + 悬浮扫码）、
  `/station/**` 站点端、`/platform/**` 平台端（240px 深色侧边栏，768–1200px 收为 64px 图标条）。
- **<768px 表格一律转卡片列表，禁止横向滚动**（设计书 10.5）。
- 每个页面都要落实三态：加载用**骨架屏**（占位尺寸与真实内容一致，不用整页 spinner）、
  空态给「图标 + 一句话 + 主操作」、错误态给「重试」按钮。
