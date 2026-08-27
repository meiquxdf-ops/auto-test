# new-auto-test 协议与产品规格（冻结）

独立新项目，不兼容旧 Hessian / 旧 Java Agent。

## 规模与约束

- 设计目标：1000 Agent，全局同时 RUNNING 约 50
- Server 单实例
- 第一期无登录、无 Agent token（内网）
- 任意 shell：`bash -c <command>`
- 每 execution 日志上限 5MB（尾部），截断必须在 API/SSE/UI 标明
- 默认一机一任务，可配最大 4；仅空闲时可改并发
- 只调未开始任务的顺序；不抢占正在跑的
- 不自动重试
- 附件不做

## 身份

- `agentId`：安装时生成 UUID，落盘 `/var/lib/atagent/agent-id`
- `displayTag`：一台一名字，可与 agentId 互相解析；重名拒绝
- `sessionId`：本条 TCP 连接
- `bootId`：进程启动代
- `dispatchToken` + `executeId`：一次下发

同 agentId 第二条连接：ping 旧连接 5s，活着则拒新连接并 `dup_session`；旧连接无响应则接管。

## TCP 帧（Agent :9800）

`[4 字节大端长度 N][N 字节 UTF-8 JSON]`，单帧 ≤ 1MiB。

信封：

```json
{"v":1,"t":"req","id":42,"m":"hello","a":{}}
{"v":1,"t":"rsp","id":42,"ok":true,"r":{}}
{"v":1,"t":"rsp","id":42,"ok":false,"e":{"c":"busy","msg":"..."}}
```

| 方向 | m | 语义 |
|---|---|---|
| A→S | hello | 注册：agentId, bootId, ver, aliases, running, lastEvtId, lastLog |
| A→S | hb | 心跳 + running；Server 续租约 |
| A→S | log | executeId, token, fromSeq, lines[] |
| A→S | evt | 事件批量，(agentId,evtId) 幂等 |
| A→S | fin | 结束帧，可靠重发直到 ACK |
| S→A | exec | ACK=已受理；busy/dup_token |
| S→A | cancel | 按 token 杀进程组 |
| S→A | stop | 停本机全部执行 |
| S→A | ping | 探活 |

## HTTP（:8080）

无鉴权。CORS 放开本机开发端口。

- `POST /api/tasks` 创建任务（command, cwd, env, targets[], conditionConfig, operator, timeoutSec, priority）
- `GET /api/tasks` 列表
- `POST /api/tasks/{id}/cancel`
- `POST /api/tasks/{id}/rerun` body `{mode:"inplace"|"new"}`
- `POST /api/tasks/reorder` 仅 pending
- `GET /api/agents`
- `PATCH /api/agents/{agentId}` 改 tag / 空闲时 concurrency
- `POST /api/agents/{agentId}/restart`
- `POST /api/agents/{agentId}/stop`
- `GET /api/executions/{id}`
- `GET /api/executions/{id}/logs?from=&limit=`
- `GET /api/timeline?agentId=&executeId=`
- `GET /api/sse/agents` snapshot+patch
- `GET /api/sse/exec/{id}?from=` 日志

## 状态机

execution：`pending → dispatching → running → pass|fail|block|exception|canceled`

- 失联：running 的子状态 disconnected，不是终态
- 进程没了 / 超时杀掉 → exception
- 用户取消 → canceled
- 租约到期且对账确认进程不在 → exception

## 判定（最后一行）

算子：equals, not-equals, include, regex。先匹配先赢。`other` 为都不匹配时的状态。

- 无 conditionConfig：exitCode==0 → pass，否则 fail
- 有配置未命中：有 other 用 other（限 pass/fail/block/exception）；否则最后一行等于 `"0"` → pass，否则 fail

## 调度

DB 行级租约 CAS。`exec` 只 ACK 收到。正常终态只认 `fin`。禁止按 IP 随机挑连接。ingest 时把 tag/agentId 解析并固化到 execution。

## 前端页面（必须完整好用）

1. 总览 Dashboard
2. 机器列表（在线、失联、在跑、并发、重启/停止）
3. 任务与队列（创建、拖拽排序 pending、取消、两种重跑）
4. 执行详情 + 实时日志（截断横幅、虚拟滚动）
5. 时间线（机器入口 + 执行入口，agent/server 事件）
6. 测试下发页（填命令/cwd/env/目标/condition，一键下发并跟日志）

中文 UI，Element Plus + Vue3 + Vite。
