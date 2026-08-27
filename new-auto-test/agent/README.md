# atagent

new-auto-test 的执行端：单个静态二进制，常驻在被测机器上，与 Server 保持一条 TCP 长连接，接收下发的 shell 命令、回传日志与结果。

只依赖 Go 标准库，没有任何第三方模块。

## 构建

```bash
go build -o atagent ./cmd/atagent
```

发布用的静态二进制（linux/amd64）：

```bash
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
  -ldflags "-s -w \
    -X github.com/atest/atagent/internal/version.Version=$(git describe --tags --always 2>/dev/null || echo 0.1.0) \
    -X github.com/atest/atagent/internal/version.Commit=$(git rev-parse --short HEAD) \
    -X github.com/atest/atagent/internal/version.BuildTime=$(date -u +%FT%TZ)" \
  -o atagent ./cmd/atagent
```

`CGO_ENABLED=0` 保证纯静态链接，产物可直接拷贝到任意 glibc/musl 机器运行：

```bash
file atagent   # ELF 64-bit LSB executable, x86-64, statically linked
```

`make build` / `make static` / `make test` 是上面命令的快捷方式。

## 子命令

| 命令 | 说明 |
|---|---|
| `atagent run` | 连接 Server 并开始接单（不写子命令时的默认行为） |
| `atagent status` | 打印本机 Agent 状态 |
| `atagent version` | 打印版本信息，`-json` 输出机器可读格式 |

`run` 与 `status` 的公共参数：

| 参数 | 说明 |
|---|---|
| `-config` | 配置文件路径，默认 `/etc/atagent/config.yaml` |
| `-data-dir` | 数据目录，默认 `/var/lib/atagent`；开发时用 `-data-dir ./data` |
| `-server` | Server 地址 `host:port`，默认 `127.0.0.1:9800` |
| `-tag` | 本机展示名（displayTag） |
| `-socket` | 状态查询用的 unix socket，默认 `<data-dir>/atagent.sock` |
| `-concurrency` | 并发执行数 1..4，默认 1 |
| `-log-level` | `debug｜info｜warn｜error`，默认 `info` |

`run` 另有 `-print-config`：打印解析后的最终配置并退出，用来排查“配置到底生效了没有”。

开发时的典型跑法：

```bash
./atagent run -data-dir ./data -server 127.0.0.1:9800 -tag dev-01 -log-level debug
./atagent status -data-dir ./data
```

## 配置

优先级从低到高：**内置默认值 < 配置文件 < 环境变量 < 命令行参数**。

配置文件是 YAML 的一个小子集（标量、一层嵌套 map、字符串列表、`#` 注释），由内置解析器读取，因此不需要 YAML 依赖。键名大小写和 `-`/`_` 都不敏感，`data_dir`、`dataDir`、`data-dir` 等价。

```yaml
# /etc/atagent/config.yaml
server: 127.0.0.1:9800
tag: build-01
concurrency: 1          # 1..4，超出会被截断到 4
dataDir: /var/lib/atagent
shell: /bin/bash
heartbeatSec: 5
maxLogBytes: 5MB        # 每个 execution 本地保留的日志尾部
killGraceSec: 5         # SIGTERM 到 SIGKILL 的等待时间
killOnShutdown: true    # Agent 退出前是否结束正在跑的执行
logLevel: info
aliases:                # 除 tag 外额外上报的别名
  - build-01.internal
env:                    # 注入到每次执行的公共环境变量
  CI: "true"
```

`config.example.yaml` 是可直接复制的完整样例。

环境变量：

| 变量 | 说明 |
|---|---|
| `ATEST_SERVER` | Server 地址，默认 `127.0.0.1:9800` |
| `ATEST_TAG` | 本机展示名 |
| `ATEST_CONFIG` | 配置文件路径 |
| `ATEST_DATA_DIR` | 数据目录 |
| `ATEST_CONCURRENCY` | 并发执行数 |
| `ATEST_SHELL` / `ATEST_SOCKET` / `ATEST_STATUS_FILE` / `ATEST_LOG_LEVEL` | 同名配置项 |
| `ATEST_HEARTBEAT_SEC` / `ATEST_KILL_GRACE_SEC` / `ATEST_MAX_LOG_BYTES` / `ATEST_KILL_ON_SHUTDOWN` | 同名配置项 |

默认配置文件不存在不算错误（只用环境变量也能跑）；但 `-config` 指定的文件不存在会直接报错退出。

## 数据目录

```
/var/lib/atagent/
├── agent-id           # 安装时生成的 UUID，机器身份，不要删
├── evt-seq            # 事件号水位，保证 evtId 跨重启不重复
├── atagent.sock       # 状态查询 unix socket
├── status.json        # 状态快照文件（socket 不可用时的兜底）
├── journal/           # 每个 execution 的日志尾部
└── spool/fin/         # 尚未被 Server 确认的 fin 帧
```

`agentId` 首次启动时生成并落盘，之后一直复用；把 `agent-id` 一起拷到另一台机器会造成两台机器抢同一个身份。

## 与 Server 的协议

帧格式 `[4 字节大端长度 N][N 字节 UTF-8 JSON]`，单帧上限 1MiB。信封与 `docs/protocol.md` 一致：

```json
{"v":1,"t":"req","id":42,"m":"hello","a":{}}
{"v":1,"t":"rsp","id":42,"ok":true,"r":{}}
{"v":1,"t":"rsp","id":42,"ok":false,"e":{"c":"busy","msg":"..."}}
```

### Agent → Server

| m | `a` 主要字段 | `r` |
|---|---|---|
| `hello` | `agentId, bootId, ver, aliases[], tag, host, os, arch, pid, startedAt, concurrency, running[], lastEvtId, lastLog{}, pendingFin[], reconnect` | 见下方 ControlResult |
| `hb` | `agentId, bootId, ts, concurrency, running[], pendingFin` | 同上 |
| `log` | `agentId, executeId, dispatchToken, fromSeq, lines[], truncated, droppedLines` | `{"ackSeq":N}` |
| `evt` | `agentId, bootId, events[]` | `{"ackEvtId":N}` |
| `fin` | `agentId, bootId, executeId, dispatchToken, exitCode, signal, reason, err, startedAt, finishedAt, lastLine, logSeq, logBytes, truncated, attempt` | 任意成功响应即视为 ACK |

- `running[]` 元素：`{executeId, dispatchToken, pid, startedAt, logSeq, ackedSeq}`
- `lines[]` 元素：`{seq, ts, s, x}`，`s` 取 `o`(stdout) / `e`(stderr) / `x`(Agent 自己的说明行，如“已发送 SIGTERM”)
- `events[]` 元素：`{evtId, ts, kind, executeId, dispatchToken, msg, data{}}`，`(agentId, evtId)` 幂等
- `fin.reason` 取 `exited｜canceled｜stopped｜timeout｜start_failed｜agent_shutdown`

ControlResult（`hello` / `hb` 的响应，所有字段可选，回 `{}` 也合法）：

```json
{
  "sessionId": "...",        // 本条连接的会话号，仅用于日志
  "serverTime": 1730000000,
  "tag": "build-01",         // Server 侧改名后下发，Agent 采纳
  "concurrency": 2,          // 仅在本机空闲时生效，否则忽略并告警
  "cancel": ["token-a"],     // 对账结论：这些 token 不要再跑了
  "logAck": {"exec-1": 120}, // 断线重连后告诉 Agent 日志收到哪一条
  "evtAck": 88
}
```

### Server → Agent

| m | `a` | `r` | 失败码 |
|---|---|---|---|
| `exec` | `executeId, dispatchToken, taskId, command, cwd, env, timeoutSec, shell` | `{accepted, executeId, dispatchToken, ackedAt}` | `busy`、`dup_token`、`bad_request` |
| `cancel` | `executeId, dispatchToken, reason` | `{killed, executeId, dispatchToken, msg}` | `bad_request` |
| `stop` | `reason` | `{killed:N}` | — |
| `ping` | — | `{agentId, bootId, ts, running}` | — |

为了兼容不同的 Server 写法，`exec` / `cancel` 的字段名可以用别名：`dispatchToken` 也接受 `token`，`executeId` 也接受 `execId` / `executionId`，`command` 也接受 `cmd`，`cwd` 也接受 `workDir`，`timeoutSec` 也接受 `timeout`；`env` 既接受 `{"K":"V"}` 也接受 `["K=V"]`。

## 执行语义

- **先 ACK 再执行**：`exec` 的响应只代表“已受理并占住槽位”，进程在响应帧写出之后才启动。执行结果一律由 `fin` 决定，ACK 不代表任何终态。
- **并发**：同一时刻运行的执行数不超过配置的 `concurrency`（默认 1，上限 4）。超出时用 `busy` 拒绝；`dispatchToken` 或 `executeId` 重复用 `dup_token` 拒绝。改并发只在本机空闲时生效。
- **进程组**：命令通过 `bash -c <command>` 启动，并 `Setpgid` 单独成组。取消、超时、停机都是对整个进程组发信号，所以 `cmd &` 起的后台子进程不会变成孤儿。
- **取消**：`cancel` 按 `dispatchToken` 找到执行，先给进程组 `SIGTERM`，`killGraceSec` 秒后仍未退出则 `SIGKILL`。`stop` 是对本机所有执行做同样的事。
- **超时**：`timeoutSec > 0` 时到点按取消流程处理，`fin.reason=timeout`。
- **退出码**：正常退出取进程退出码；被信号打断记 `128+signal` 并在 `fin.signal` 带上信号名；`cwd` 不存在等启动失败记 `exitCode=-1`、`reason=start_failed`，失败原因同时写进执行日志。
- **环境变量**：Agent 进程环境 → 配置里的 `env` → 本次下发的 `env` 逐层覆盖，另外注入 `ATEST_AGENT_ID`、`ATEST_BOOT_ID`、`ATEST_EXECUTE_ID`、`ATEST_DISPATCH_TOKEN`、`ATEST_TASK_ID`、`ATEST_TAG`。

## 日志与可靠性

- **本地 journal**：每个 execution 的输出按行写入 `journal/<executeId>.log`，只保留尾部 5MB（`maxLogBytes`），超限时从最早的行开始丢，磁盘和内存都不会被话痨命令撑爆。用于判定的“最后一行”单独保留，不会被截断吃掉。
- **两种截断信号，含义不同**：`truncated` 表示这次执行的输出总量超过了保留上限（UI 该标注截断）；`log` 帧的 `fromSeq` 表示这一批从哪条序号之后接上，只有它对不上上次 ACK 的位置时才说明真有日志没送达——连接正常时日志是边产生边发走的，即使输出远超 5MB 也不会丢。`seq` 全程连续计数，Server 可以据此对齐。
- **批量上报**：日志按最多 500 行 / 384KB 一批发送，远低于 1MiB 帧上限；有新输出时立即触发，空闲时按 200ms 节奏收敛。
- **fin 可靠重发**：进程结束后 fin 先落盘到 `spool/fin/`，再上报；只有收到成功响应才删除。断线、Server 重启、Agent 重启都不会丢结果——重启后 spool 里的 fin 会继续重发。fin 会等自己那条执行的日志发完再发（最多等 30 秒），保证 Server 先看到日志再看到终态。
- **断线重连**：连接断开后按指数退避加随机抖动重连（默认 500ms 起、30s 封顶），连续稳定 60 秒以上的连接会重置退避。1000 台机器同时重连时抖动能避免踩踏。
- **重连对账**：`hello` 会带上本机正在跑的执行（含 pid、日志水位）。**短暂断线不会杀进程**：本地进程照常跑、日志照常写 journal，重连后补发。只有 Server 在 `hello` / `hb` 响应里明确用 `cancel` 点名的 token 才会被结束。
- **停机**：收到 `SIGTERM`/`SIGINT` 后停止接单，默认结束正在跑的执行（`killOnShutdown: false` 可改成留着不管），随后用还活着的连接把剩余日志与 fin 发完再退出。再按一次 Ctrl-C 立即退出。

## 状态查询

Agent 在 `<data-dir>/atagent.sock` 上提供只读 HTTP 接口（`/status`、`/healthz`），同时每 5 秒把同样的快照写到 `<data-dir>/status.json`。

```bash
atagent status                    # 优先读 socket，读不到自动回落到状态文件并标注数据年龄
atagent status -json              # 原始 JSON
curl --unix-socket /var/lib/atagent/atagent.sock http://atagent/status
```

输出包含身份与版本、连接状态与重连次数、并发与在跑执行（pid、耗时、日志进度、是否截断）、待发 fin 与事件数。

socket 同时是单实例保护：socket 上有活着的 Agent 时，第二个 `atagent run` 会直接报错退出，不会出现两个进程抢同一个 `agentId`。

## systemd

```ini
[Unit]
Description=new-auto-test agent
After=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/atagent run
Restart=always
RestartSec=3
# 执行是独立进程组，KillMode=process 可避免 systemd 绕过 Agent 直接清理子进程
KillMode=process
TimeoutStopSec=30
StateDirectory=atagent

[Install]
WantedBy=multi-user.target
```

## 代码结构

```
cmd/atagent/          命令行入口（run / status / version）
internal/agentd/      运行时：会话循环、下发处理、上报、状态快照
internal/client/      TCP 会话（req/rsp 多路复用）、拨号与退避
internal/proto/       帧编解码、信封与各消息结构
internal/task/        执行管理：进程组、并发、取消、超时、fin 生成
internal/journal/     日志尾部 journal
internal/spool/       fin 落盘与重发队列
internal/events/      时间线事件缓冲与 evtId 水位
internal/status/      unix socket + 状态文件
internal/config/      配置解析（含精简 YAML 解析器）
internal/ident/       agentId / bootId
internal/logx/        分级日志
```

## 测试

```bash
go test ./...
go test -race ./...
```

`internal/agentd` 里带一个假 Server，覆盖端到端路径：下发与 ACK、日志流、fin、取消杀进程组、超时、并发拒绝、断线重连不杀进程、fin 重发直到确认。
