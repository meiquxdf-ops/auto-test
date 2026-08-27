# new-auto-test 运维台（frontend）

Vue 3 + Vite + TypeScript + Vue Router + Element Plus 实现的分布式测试执行平台运维台。
接口与 `docs/protocol.md` 完全一致，无登录、中文 UI。

## 启动

```bash
npm install
npm run dev     # http://127.0.0.1:5173
npm run build   # 类型检查 + 产物输出到 dist/
```

- 开发态走 vite 代理：`/api` → `http://127.0.0.1:8080`。
  改后端地址：`VITE_API_BASE=http://10.0.0.12:8080 npm run dev`。
- 生产构建默认直连 `http://127.0.0.1:8080`（同样可用 `VITE_API_BASE` 覆盖）。
- 运行期还能在页头「接口地址」里临时改，存 `localStorage.nat.apiBase`，跨机调试不用重新构建
  （需要 Server 放开 CORS）。

## 页面

| 路由 | 页面 | 内容 |
|---|---|---|
| `#/dashboard` | 总览 | 在线机器数、运行中执行、失败/阻塞/异常、排队数、并发水位、正在执行、需要关注、最近任务、状态分布、近期事件 |
| `#/agents` | 机器 | 状态灯（在线/忙碌/失联/离线）、改 tag、空闲时改并发 1-4、重启 Agent、停止当前任务、点机器名看时间线 |
| `#/tasks` | 任务队列 | 状态筛选、**按机器筛选**、创建任务、pending 调序、取消、原地重跑、重跑为新记录；任务状态按子执行聚合（pass/fail/block，不会把 finished 显示成排队中） |
| `#/executions/:executeId` | 执行详情 | 元数据、结果徽章、实时日志终端、5MB 截断横幅 |
| `#/timeline` | 时间线 | 按 executeId / agentId 查询，agent 左、server 右，展示 token / session / bootId |
| `#/playground` | 测试下发 | 选机器 + 填命令 + 可选 condition，一键下发，右侧立刻跟状态和日志 |

侧栏五个入口都是真实页面，没有占位首页；`/` 重定向到总览，未知路由有 404 页。

## 实现要点

- **axios 封装**（`src/api/http.ts`）：统一 baseURL、错误归一化成 `ApiError`、`toastError` / `toastOk`，
  并兼容 `{code,data}` / `{data}` / 裸对象 / `{items:[]}` 等返回包装。
- **字段归一化**（`src/api/normalize.ts`）：状态别名、时间戳（秒/毫秒/ISO）、字段别名统一收敛，
  页面只消费 `src/api/types.ts` 里的领域模型。
- **SSE**（`src/api/sse.ts`）：`EventSource` + Last-Event-ID 续传。
  `onerror` **不** `close()` —— 浏览器原生重连会自动带上 `Last-Event-ID` 请求头；
  只有 `readyState` 变成 `CLOSED` 时才由前端做指数退避重连，并把记录到的 lastEventId
  通过查询参数回传，兼容不认该请求头的实现。
- **日志终端**（`src/components/LogTerminal.vue`）：深色等宽、固定行高虚拟滚动（换行模式退化为尾部分片渲染）、
  自动滚动开关（用户上翻自动暂停、回底自动恢复）、关键字过滤高亮、时间戳、复制、下载、
  以及醒目的「仅保留末 5MB」截断横幅。
- **实时日志流**（`src/composables/useExecutionLog.ts`）：先分页拉历史日志，再用 SSE 从 `nextSeq` 续传，
  按 seq 去重，`requestAnimationFrame` 批量刷新，前端另有 20 万行内存上限保护。
- **机器状态**（`src/stores/agents.ts`）：`/api/sse/agents` 的 snapshot + patch 增量，
  多页面共享一条连接（引用计数），另有低频 REST 对账兜底。

## 前端调用的接口

全部来自 `docs/protocol.md`，请求体形状如下（响应做了宽松兼容，字段别名见 `normalize.ts`）：

| 方法 | 路径 | 请求体 / 参数 |
|---|---|---|
| GET | `/api/agents` | - |
| PATCH | `/api/agents/{agentId}` | `{displayTag?, concurrency?}` |
| POST | `/api/agents/{agentId}/restart` | - |
| POST | `/api/agents/{agentId}/stop` | - |
| GET | `/api/tasks` | `?status=&keyword=&limit=&offset=` |
| POST | `/api/tasks` | `{command, cwd?, env?, targets[], conditionConfig?, operator?, timeoutSec?}` |
| POST | `/api/tasks/{id}/cancel` | - |
| POST | `/api/tasks/{id}/rerun` | `{mode:"inplace"｜"new"}` |
| POST | `/api/tasks/reorder` | `{ids:[taskId...]}`，按目标顺序从前到后，仅 pending |
| GET | `/api/executions/{id}` | - |
| GET | `/api/executions/{id}/logs` | `?from=&limit=` |
| GET | `/api/timeline` | `?agentId=&executeId=&limit=` |
| SSE | `/api/sse/agents` | 事件名 `snapshot` / `patch`（未具名的 message 按增量处理） |
| SSE | `/api/sse/exec/{id}` | `?from=`，事件名 `log` / `status` / `truncated` / `end`（未具名的 message 按日志处理） |

约定补充：

- `GET /api/tasks` 每条任务若带上 `executions[]`，队列页可直接展开看执行明细、进度条与结果分布；
  不带时前端退化为按任务状态展示。
- `GET /api/tasks/{id}` 不是协议里的接口，测试下发页会先试它，404 就回列表里捞。
- 日志分页返回 `{lines:[{seq,ts,stream,text}], nextSeq, truncated, droppedBytes, totalBytes}`；
  `truncated` 为 true 时终端顶部会挂「仅保留末 5MB」横幅。
- 时间线事件带 `source: "agent"｜"server"` 时按其分侧；没有该字段时，带 `evtId` 的算 agent 侧。

## 状态色

| 状态 | 颜色 | 状态 | 颜色 |
|---|---|---|---|
| pass 通过 | 绿 | exception 异常 | 紫 |
| fail 失败 | 红 | canceled 已取消 | 灰 |
| block 阻塞 | 橙 | running 执行中 | 蓝 |

`pending` 排队中为石板灰、`dispatching` 下发中为青色；机器状态灯：在线绿、忙碌蓝、失联橙、离线灰。

## 目录

```
src/
  api/          axios 封装、类型、字段归一化、SSE 客户端、各资源接口
  components/   状态徽章、机器状态灯、空状态、日志终端、env / condition 编辑器、
                创建任务抽屉、队列排序抽屉、时间线列表等
  composables/  useExecutionLog（历史日志 + SSE 续传）
  stores/       agents（共享 SSE + REST 对账）
  views/        六个页面 + 404
  utils/        时间/字节格式化、状态色、聚合统计
```
