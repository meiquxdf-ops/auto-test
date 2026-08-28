# 开放接口接入手册（Open API）

面向**测试计划系统等外部接入方**：你有一批测试命令要下发到测试机执行，希望通过 HTTP
创建任务、拿到结果通知、拉取日志、回收产物文件。本手册按接入顺序把这些事讲完，
不是协议逐条罗列——协议冻结稿见 [`protocol.md`](protocol.md)。

配套 UI：前端 `#/open` 是**开放查询**（按 requestId 看整批进度），`#/open/debug` 是
**接入调试**（对每个接口发真实请求、看原始状态码与响应体、复制等价 curl）。
接入联调时建议开着这两个页面对照。

> 本系统是全新实现，**不兼容**旧 auto-test 的 `/upload` 上传、MinIO 对象存储与
> RocketMQ 消息通知。老接入方迁移过来时，这三样都要换成本手册里的对应物：
> 附件上传/下载走 Server 本地盘的 `files` 接口，结果通知走 `callbackUrl` HTTP 回调。

## 1. 基本约定

- **Base URL**：`http://<server>:8080`，纯 HTTP/JSON。下文示例统一写 `$BASE`。
- **无鉴权**：第一期内网使用，没有 token / 签名，网络可达即可调用。
- 请求体、响应体均为 UTF-8 JSON；上传接口除外（multipart，见 [第 7 节](#7-附件产物回传与下发新)）。
- 错误响应统一为：

```json
{ "code": "conflict", "message": "requestId 已存在: ci-001", "path": "/api/tasks", "status": 409 }
```

常见状态码速查：

| 状态码 | 含义 |
|---|---|
| 400 | 参数错误（command 为空、目标机器不存在、requestId 格式错、路径/查询参数类型不符、JSON 体读不出来等） |
| 404 | taskId / executeId / fileId 不存在；路径本身不存在时是 `{"code":"not_found","message":"no such path"}` |
| 405 | 该路径不支持这个 HTTP 方法（响应带 `Allow` 头） |
| 409 | requestId 重复；或对仍在执行中的任务重跑 |
| 415 | Content-Type 不对（JSON 接口要 `application/json`，上传接口要 `multipart/form-data`） |
| 413 | 附件超过单文件 32MB 上限 |
| 429 | 附件上传并发已满，稍后重试 |
| 503 | Server 附件写盘失败（磁盘/IO 故障） |

4xx 一律是「这个请求本身有问题」，原样重试不会变好；只有 429 和 5xx 值得退避重试。

## 2. requestId：调用方幂等键

创建任务时可以（批量则**必须**）带一个 `requestId`，它是你和本系统之间的对账键：

- 格式 `^[A-Za-z0-9._-]{1,64}$`（字母、数字与 `. _ -`，1~64 位），不符返回 400。
- **全局唯一，一次性消费**：重复创建返回 409，且不会创建任何东西。
  建议用「业务前缀 + 日期 + 序号」之类可读且不撞的键，如 `plan-20260828-0007`。
- 单条创建可以省略：Server 会铸一个 UUID 当 requestId 并在响应里回显，
  之后照样可以用它查询。批量创建省略则整单 400。
- 之后凭 requestId 一键查询该次创建的**全部**任务与执行明细（见第 4 节），
  也可以直接把用户引到 `#/open?requestId=<id>` 页面看进度。

## 3. 创建任务

### 3.1 单条：`POST /api/tasks`

```bash
curl -X POST "$BASE/api/tasks" -H 'Content-Type: application/json' -d '{
  "requestId": "plan-20260828-0007",
  "callbackUrl": "http://plan.internal/notify",
  "command": "cd /opt/suite && bash run.sh && echo 0",
  "targets": ["qa-node-01", "qa-node-02"],
  "cwd": "/tmp",
  "env": { "SUITE": "smoke" },
  "timeoutSec": 1800,
  "operator": "plan-bot",
  "conditionConfig": { "conditions": [ { "operator": "equals", "value": "0", "status": "pass" } ], "other": "fail" }
}'
```

- 必填只有 `command` 和 `targets`；`targets` 里的每一项既可以是机器 tag 也可以是
  agentId，创建时就解析，未知目标整条 400。
- `requestId` / `callbackUrl` / `cwd` / `env` / `timeoutSec`（默认 3600）/ `operator`（可空）/
  `conditionConfig`（按最后一行 stdout 判定，缺省 exitCode==0 → pass）都可选。
- 响应是任务对象：`id`（即 taskId，取消/重跑用）、`requestId`（省略时为服务端铸的 UUID）、
  `status`、`executions[]`（每台目标机一条，含 `executeId`）等。

### 3.2 批量：`POST /api/tasks/batch`

一次 HTTP 建多条任务（不同命令/不同目标机），整批共用一个 requestId，最多 100 条：

```bash
curl -X POST "$BASE/api/tasks/batch" -H 'Content-Type: application/json' -d '{
  "requestId": "plan-20260828-0008",
  "callbackUrl": "http://plan.internal/notify",
  "items": [
    { "name": "冒烟", "command": "bash smoke.sh", "targets": ["qa-node-01"] },
    { "name": "回归", "command": "bash regress.sh", "targets": ["qa-node-02"], "timeoutSec": 7200 }
  ]
}'
```

语义要点（联调时最容易踩的地方）：

- **逐条部分成功**：某条 items 无效（command 为空、目标不存在……）只拒那一条，
  落在响应 `errors[{index, message}]` 里，其余照常创建。**必须检查 `errors[]`**，
  HTTP 200 不代表全部创建成功。
- **全部条目无效** → 整单 400，**requestId 不被占用**，修正 payload 后可用同一个
  requestId 原样重试。
- requestId 缺失 / 格式错 / 重复 → 尚未看条目就整单拒绝（400 或 409）。
- 响应：`{ "requestId": "...", "tasks": [任务对象...], "errors": [...] }`。

## 4. 查询：`GET /api/tasks?requestId=`

```bash
curl "$BASE/api/tasks?requestId=plan-20260828-0008"
```

返回该 requestId 名下全部任务及执行明细（上限 200 条；`includeExecutions=false`
可省流量只要任务壳）。每条执行有 `executeId`、`status`、`exitCode`、`lastLine` 等；
任务上有 `statusCounts`（各执行状态计数）与 `attachmentCount`（附件数）。

状态机：执行 `pending → dispatching → running → pass|fail|block|exception`，
用户取消为 `canceled`；**失联是 running 的子状态，不是终态**。任务状态是聚合：
`pending / running / finished / canceled`，`finished` 后看每台机器的执行状态拿测试结果。

没有回调时轮询这个接口即可；页面版是 `#/open?requestId=<id>`，可以直接发给用户。

## 5. 取消与重跑

```bash
curl -X POST "$BASE/api/tasks/{taskId}/cancel?operator=plan-bot"
curl -X POST "$BASE/api/tasks/{taskId}/rerun" -H 'Content-Type: application/json' -d '{"mode":"inplace"}'
```

- **取消**：未开始的执行直接置 `canceled`；在跑的杀进程组后置 `canceled`。
  operator 拼在 URL 上，可省略。
- **重跑** `mode=inplace`：同一条任务原地重跑，**清空旧执行记录与日志**；任务还有执行
  在跑时返回 409。配了 callbackUrl 的任务重新到终态会**再次回调**。
- **重跑** `mode=new`：复制出一条全新任务，**Server 铸一个新的 requestId**（响应里回显），
  历史保留。注意：新任务**不会**出现在原 requestId 的查询结果里，接入方要记下新
  requestId 才能跟踪它。

## 6. 结果回调 callbackUrl

创建时带 `callbackUrl`（http/https，≤1024 字符），任务到终态（全部执行结束，含取消）后
Server 向它 **POST 一次** JSON 结果：

- **2xx 算送达**；非 2xx 或网络失败按 **1s 起指数退避重试**（1s/2s/4s/8s/16s，
  共 6 次尝试），仍失败则放弃并把失败原因记在任务上（查询接口的
  `callbackStatus` / `callbackLastError` 可见）。
- 回调体形如：

```json
{
  "event": "task.terminal",
  "taskId": 42,
  "requestId": "plan-20260828-0007",
  "name": "冒烟",
  "status": "finished",
  "statusCounts": { "pass": 1, "fail": 1 },
  "totalCount": 2,
  "executions": [
    {
      "executeId": "1f0c8f4be5ab4b9c9a1c2d3e4f5a6b7c",
      "agentTag": "qa-node-01",
      "status": "pass",
      "exitCode": 0,
      "lastLine": "0",
      "reason": null,
      "matchedRule": "equals:0",
      "startedAt": "2026-08-28T03:00:01Z",
      "finishedAt": "2026-08-28T03:02:11Z"
    }
  ],
  "ts": 1787269331000
}
```

（另含 command / cwd / env / conditionConfig / targets / timeoutSec / operator / createdAt / finishedAt 等原样字段。）

**注意：回调里每台机器只有 `lastLine`（最后一行 stdout，截 4096 字符），不含完整日志。**
需要全量日志的话，收到回调后按 `executions[].executeId` 拉：

```bash
curl "$BASE/api/executions/{executeId}/logs?from=0&limit=1000"
```

响应带 `lines[]` 与游标，翻页直到取完；单次执行日志上限 5MB（超出保留尾部并标注截断）。

**回调验签（可选）**：Server 配置了 `atest.callback.hmac-secret` 时，每次回调 POST 都带
两个签名头（未配置则一个都没有，行为与现在完全一致）：

| 头 | 值 |
|---|---|
| `X-Atest-Signature` | `HMAC-SHA256(secret, 原始请求体字节)` 的小写 hex |
| `X-Hub-Signature-256` | 同一摘要的 GitHub webhook 风格：`sha256=<hex>` |

接收方验签：用与运维约定的同一密钥，对**收到的原始 body 字节**（先别做 JSON
反序列化再序列化，字节要原样）重算 HMAC-SHA256 并与签名头比对（建议常量时间比较）。
Python 示例：

```python
import hashlib, hmac
expected = hmac.new(secret.encode(), raw_body_bytes, hashlib.sha256).hexdigest()
ok = hmac.compare_digest(expected, request.headers["X-Atest-Signature"])
```

其他要点：回调是**每次到终态一次**——inplace 重跑后再次到终态会再发一次；单次投递
超时 10s；回调地址要能被 **Server** 访问到（是 Server 发起的出站 POST）。

## 7. 附件：产物回传与下发（新）

测试脚本产出的报告、截图、日志包等，可以直接回传到 Server 上，接入方再统一下载。
存 Server 本地磁盘（`atest.attachments.dir`，默认 `./data/attachments/`），
**不是** MinIO / 对象存储。

### 7.1 脚本回传（在测试机上）

Server 下发任务时会注入环境变量，脚本一行 curl 即可：

```bash
curl -sf -F "file=@report.tar.gz" "$ATEST_HTTP_BASE/api/executions/$ATEST_EXECUTE_ID/files"
```

即 `POST /api/executions/{executeId}/files`，multipart 字段名固定为 `file`；
executeId 不存在返回 404。附件挂在这次执行上，并归属其任务。

注入到执行环境的变量（可直接在命令/脚本里用）：

| 变量 | 含义 |
|---|---|
| `ATEST_HTTP_BASE` | Server HTTP 地址（来自配置 `atest.http.public-base`），回传附件用 |
| `ATEST_EXECUTE_ID` | 本次执行 id，回传附件、拉日志用 |
| `ATEST_TASK_ID` | 所属任务 id |
| `ATEST_AGENT_ID` / `ATEST_TAG` | 本机身份 / 展示名 |
| `ATEST_BOOT_ID` / `ATEST_DISPATCH_TOKEN` | Agent 启动代 / 本次下发凭据（一般用不到） |

> **⚠️ 必须把 `atest.http.public-base` 配成测试机（Agent）能访问到的 Server 地址。**
> 它的默认值是 `http://127.0.0.1:8080`——只要 Agent 和 Server 不在同一台机器上，
> 脚本里的 `$ATEST_HTTP_BASE` 就会指向**测试机自己的 localhost**，上传必然失败
> （典型症状：脚本里 curl `Connection refused`，Server 侧毫无记录）。
> 部署多机时在 Server 配置里改成如 `http://10.0.0.5:8080`，改完重启 Server，
> 之后**新下发**的任务才会注入新地址。任务 env 里显式给了 `ATEST_HTTP_BASE` 时以任务的为准。

### 7.2 调用方直传 / 补附件

不经过脚本，运维台或接入系统也可以直接给任务挂文件（同样 multipart 字段 `file`）：

```bash
curl -sf -F "file=@plan.xlsx" "$BASE/api/tasks/{taskId}/files"
```

### 7.3 列表与下载

```bash
# 任务下全部附件元数据：[{ id, taskId, executeId, name, size, contentType, createdAt }]
curl "$BASE/api/tasks/{taskId}/files"

# 按附件 id 下载（Content-Disposition: attachment，文件名为上传时原始名）
curl -OJ "$BASE/api/files/{fileId}"
# 浏览器内预览图片/文本加 ?inline=1（同一地址，改 Disposition 为 inline）
```

`executeId` 为空的附件是任务级直传的；非空则来自那次执行的脚本回传。

### 7.4 限制与错误

- 单文件硬上限 **32MB**，超限 **413**（`file_too_large`）。上限是容器层与应用层双保险，
  超大产物请先压缩或拆分。
- 上传落盘走专用有界线程池（默认 8 个在写 + 8 个排队），超出直接 **429**
  （`too_many_uploads`），**稍后重试即可**，不会排队拖死连接。
- 磁盘/IO 故障 **503**（`storage_unavailable`）。
- 文件名会消毒后落盘，原始名保留用于展示与下载头；同名可重复上传，各存一份。

## 8. 接入自查清单

1. 创建后**保存 requestId**（单条省略时记响应回显的 UUID），它是之后查询对账的唯一入口。
2. 批量创建 HTTP 200 后**检查 `errors[]`**，部分成功是常态语义。
3. 收到回调只代表拿到 lastLine 级别的结果，**完整日志要另拉** `GET /api/executions/{executeId}/logs`。
4. 用附件回传前，确认 **`atest.http.public-base` 不是默认的 127.0.0.1**（多机部署必改）。
5. 上传遇 413 → 压缩拆分；429 → 稍后重试；503 → 找运维看 Server 磁盘。
6. `mode=new` 重跑后记住**新的 requestId**。
7. 联调用 `#/open/debug`（接入调试）逐个接口发真实请求，跑通后把页面上的 curl 原样带回你的系统。
