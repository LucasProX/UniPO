# 前端开发指南（Vue 3）

> 目标：新增一个页面/功能时，不用先读 4000 行代码就知道该改哪几个文件。
> 后端接口约定见 [`docs/API.md`](API.md)，架构决策见 [ADR-0001](adr/0001-platform-baseline.md)。

## 1. 目录分工

    frontend/src
      main.ts             应用启动、全局错误兜底
      App.vue             61 行外壳：AppShell + 全局弹窗 + 会话引导
      router/index.ts     路由表（除首页外全部懒加载）
      layouts/            布局壳（header / 左右栏 / dock）
      views/              路由视图，一个页面一个文件
      views/admin/        管理后台页面（挂在 /admin 子树）
      layouts/AdminShell.vue  后台侧边栏 + 顶栏
      components/         可复用组件：layout / modals / post / ui
      stores/             Pinia：feed / auth / messages / schedule / compose / profile
      lib/http.ts         axios、Cookie、CSRF、错误包（基础设施）
      lib/api.ts          业务端点（按 campus/auth/post/user/media 分组）
      lib/realtime.ts     WebSocket 客户端
      lib/postHelpers.ts  帖子纯函数（零 Vue 依赖）
      lib/richText.ts     富文本清洗（零 Vue 依赖）
      types.ts            手写业务类型
      api-types.ts        从后端 OpenAPI 生成，只读

## 2. 新增一个页面（三步）

1. `views/XxxView.vue`：只做「取数据 + 渲染状态」，业务逻辑放 store。
2. `router/index.ts` 加一条路由，默认用 `component: () => import('../views/XxxView.vue')`。
3. `components/layout/DockNav.vue` 如果需要入口，再加一项。

**视图必须处理三态**：`loading` / `error`（带重试）/ `empty`。
禁止用假数据兜底——接口失败就显示失败（见 AGENTS §6）。

## 3. 新增一个接口

- 只有路径和类型：加到 `lib/api.ts` 对应分组（campusApi / authApi / postApi / userApi / mediaApi）。
- 需要新的跨请求行为（重试、签名、埋点）才动 `lib/http.ts`。
- 类型先看 `api-types.ts` 里生成的结构，再同步到 `types.ts`；
  后端改了 DTO 必须跑 `npm run gen:api`，否则 CI 的 `git diff --exit-code` 会红。

## 4. Store 约定

- 一律 `defineStore('x', () => { ... })` 的 setup 写法，返回 ref / computed / 方法。
- store 不 import 组件；组件不直接拼 URL。
- 轮询/实时订阅在 store 里成对管理（start/stop），组件卸载时停掉。
- Web/App 共用 store 和 `lib/*.ts` 纯函数，别在 store 里碰 DOM。

## 5. 认证

- Cookie 是 httpOnly 的，**前端没有 token 可存**；不要写 localStorage、不要写刷新逻辑。
- 写请求的 `X-CSRF-Token` 由 `lib/http.ts` 自动加。
- 登录态判断用 `auth.isAuthenticated`（由 `/auth/me` 探出来），不要看本地存储。
- 401 会广播 `unipo:unauthorized`，auth store 自动降级为访客。

## 6. 实时

- 用 `lib/realtime.ts` 的 `openRealtimeStream({ onEvent, onState })`，它负责重连、心跳、续传。
- 浏览器走 Cookie 握手，不需要 auth 帧；App 端接 WS 时才发 `{type:'auth', token}`。
- 事件按 `event` 名字分发（目前是 `message`），不要依赖帧结构细节。

## 7. 提交前

```bash
cd frontend
npm run lint
npm run build
```

再加根目录的 `bash scripts/smoke.sh`（后端起着时）与 CI。
