# new-auto-test 排障动线（Runbook）

按"现象 → 动线 → 处置"组织。所有语义以 [`protocol.md`](./protocol.md)（冻结）为准。

## 0. 三分钟定位法

链路只有一条：**前端(:5173) → HTTP API(:8080) → 调度 → Agent TCP(:9800) → bash 进程**。
任何问题先问自己：断在哪一段？

```
页面白屏/报错 ──► 1) 前端起没起？ 2) /api 反代通不通？        → §2、§3
机器不在列表 ───► 3) Agent 进程活着吗？ 4) TCP 9800 通吗？     → §4
机器"失联" ────► 5) 心跳断了还是进程死了？（非终态，先别慌）   → §7
任务卡 pending ─► 6) 机器在线吗？并发满了吗？排队顺序对吗？     → §5
结果不符预期 ───► 7) 看判定规则和最后一行输出                  → §6
日志不全 ──────► 8) 是不是 5MB 截断？（有横幅/truncated 标记） → §6
```

先跑一遍健康速查，再进对应小节：

```bash
curl -s http://127.0.0.1:8080/actuator/health        # Server 活着吗
curl -s http://127.0.0.1:8080/api/agents | head      # 机器都注册了吗
ss -lntp | grep -E ':(8080|9800|5173)'               # 三个端口都有人听吗
```

## 1. 端口与进程速查

| 端口 | 谁在听 | 用途 |
|---|---|---|
| 8080 | Server | HTTP API + SSE + H2 console(`/h2-console`) |
| 9800 | Server | Agent 长度前缀 TCP（`[4B 大端长度][JSON]`，单帧 ≤ 1MiB） |
| 5173 | Vite | 前端 dev server，`/api` 反代到 8080 |

```bash
# 谁占了端口
ss -lntp | grep 8080
# 开发容器
cd deploy && make ps && make logs
# 生产机上的 Agent
systemctl status atagent && journalctl -u atagent -n 200 --no-pager
```

## 2. Server 起不来

动线：**看启动日志最后一屏 → 分类**。

| 日志特征 | 原因 | 处置 |
|---|---|---|
| `Port 8080 was already in use` | 端口被占 | `ss -lntp \| grep 8080` 找到进程杀掉，或换端口 `--server.port=` |
| `9800` bind 失败 | 旧 Server 没退干净 | 同上，查 9800 |
| `Database may be already in use`（H2 文件锁） | 上一个 Server 进程还握着 `server/data/atest` | 确认旧进程退出；开发环境实在不行删 `server/data/`（**数据全丢**，仅限开发） |
| Flyway `Migration checksum mismatch` | 改了已应用的迁移脚本 | 开发环境删 `server/data/` 重来；不要改已发布的 `V*.sql`，新建版本号 |
| MySQL 连接拒绝（`mysql` profile） | 地址/账号不对或库没建 | 核对 `--spring.profiles.active=mysql` 与 `MYSQL_HOST/PORT/DATABASE/USER/PASSWORD` 环境变量（见 deploy/README.md「装 Server」），先 `mysql -h.. -u..` 手工连通 |
| `ClassNotFoundException` / lombok 报错 | 依赖没拉全 | `mvn -q clean package` 重新解析；检查内网 Maven 镜像 |

确认版本：Server 需要 **Java 17+**（`java -version`）与 Maven 3.9+。

## 3. 前端起不来 / 页面请求全挂

- `npm run dev` 报 Node 版本错误 → 需要 **Node 18+**（`node -v`）。
- 页面能开但接口 404/502 → Vite 反代目标不对：默认 `http://127.0.0.1:8080`，可用环境变量
  `VITE_API_BASE` 覆盖（Docker 里是 `http://server:8080`）。
- 浏览器控制台 CORS 报错 → 只在**绕过反代直连 8080** 时才可能出现；Server 已放开本机开发端口，
  确认请求走的是 5173 的 `/api` 或直连 8080 均可，出现 CORS 说明访问的不是这两条路径。
- SSE 一直不推送 → 反代必须保持长连接、关闭压缩缓冲（仓库自带的 `vite.config.ts` 已处理）；
  自建 Nginx 反代要加 `proxy_buffering off;`。

## 4. 机器不在列表 / 连不上

动线：**测试机上查进程 → 查网络 → 查 Server 侧日志**。

```bash
# ① Agent 活着吗（生产机）
systemctl status atagent
journalctl -u atagent -f
# 开发机：make agent 的终端有没有报错

# ② 到 Server 的 9800 通吗
nc -vz <server-host> 9800

# ③ 本机身份
cat /var/lib/atagent/agent-id      # 开发模式在 agent/data/agent-id
./atagent status                    # 打印本地状态
```

常见结论：

- **`nc` 不通**：防火墙/安全组没放 9800，找运维。
- **连上就被断，日志出现 `dup_session`**：同一个 `agentId` 已有活跃连接。
  Server 的仲裁逻辑是"ping 旧连接等 5s，活着拒新的，死了才接管"。
  两种典型成因：
  1. 旧 Agent 进程没停干净 → 测试机上 `pgrep -af atagent`，把残留进程停掉（`install.sh` 升级路径会自动收掉 `/usr/local/bin/atagent` 的残留进程；其他路径的同名进程不归它管，需手工确认）；
  2. **克隆机复制了 `agent-id`** → 两台机器在抢同一个身份。在克隆机上重装：`sudo ./install.sh --new-agent-id ...`。
- **机器在列表里重复出现**：某台机器换过 `agent-id`（`--new-agent-id` 或数据目录被清）。旧记录是历史身份，忽略或后台清理。
- **改 tag 报 409**：一机一 tag、全局唯一，重名拒绝。换名字再提。

## 5. 任务不动（卡 pending / 卡 dispatching）

动线：**先看目标机器，再看并发，再看队列顺序**。

```bash
# 目标机器在线吗、并发占满了吗
curl -s http://127.0.0.1:8080/api/agents | python3 -m json.tool
# 排队与执行状态
curl -s http://127.0.0.1:8080/api/tasks | python3 -m json.tool
# 这台机器最近发生了什么
curl -s 'http://127.0.0.1:8080/api/timeline?agentId=<agentId>' | tail
```

- **pending 不动**：
  - 目标机器 `online=false` → 回 §4；
  - `runningCount >= concurrency`（默认 1，最大 4）→ 正常排队，等前面跑完，或空闲后调大并发；
  - 想插队 → `POST /api/tasks/reorder`（**只能调 pending 的顺序**，正在跑的不抢占，页面上拖拽即可）。
- **dispatching 短暂出现是正常态**（exec 已下发在等 Agent ACK / started 事件）。长时间卡住：
  - timeline 里有 `busy` / `dup_token` 拒绝 → 会自动回队重派，观察即可；
  - Agent 恰好在此刻断线 → 等重连对账，或按 §7 处理。
- **改并发报 409**：仅**空闲时**可改（机器上没有在跑/派发中的执行）。等跑完再改。

## 6. 结果与日志问题

### 结果和预期不符

判定只看**最后一行 stdout**（见 protocol.md「判定」）：

1. 无 `conditionConfig`：`exitCode==0 → pass`，否则 `fail`；
2. 有规则：`equals / not-equals / include / regex` **先匹配先赢**；
3. 全部未命中：配了 `other` 用 `other`（限 pass/fail/block/exception）；没配 `other` 则"最后一行 == \"0\" → pass，否则 fail"。

排查：`GET /api/executions/{id}` 里有固化的 `lastLine` 和 `exitCode`，对着规则人肉过一遍即可。
注意脚本末尾多打一行空行/日志会顶掉你以为的"最后一行"。

### 日志不全

- 响应/页面出现 **truncated（截断横幅）** → 单次执行日志超过 **5MB 上限，头部被丢弃、保留尾部**。
  这是设计行为，API、SSE、UI 三处都会标明。要完整日志请让脚本自己落盘再取。
- 没截断但缺尾部 → 执行可能还没结束（`fin` 未到），或 Agent 断线中（重连后会按 `fromSeq` 补传）。
- 实时日志断流 → 看 SSE 连接（浏览器 Network 里 `/api/sse/exec/{id}`），反代缓冲问题回 §3。

### 状态语义速查

| 状态 | 含义 | 是否终态 |
|---|---|---|
| pending | 排队中，可 reorder、可取消 | 否 |
| dispatching | 已下发等 ACK/started | 否 |
| running | 在跑 | 否 |
| running(disconnected) | **失联子状态**：机器心跳断了，进程可能还在跑 | **否** |
| pass / fail / block / exception | 四种测试结果（正常终态只认 `fin`） | 是 |
| canceled | 用户取消，单独一类 | 是 |

- **超时**：到 `timeoutSec` 由 Server 下发 cancel 杀进程组，落 `exception`。
- **进程没了**（Agent 重连对账发现不在跑）→ `exception`。
- **不自动重试**：任何终态都不会自己重跑，需要人工触发重跑。

## 7. 机器失联（disconnected）

**失联不是终态**，不要急着重跑：

1. 机器只是网络抖动 → Agent 重连后带着 `running` 对账，日志按 `fromSeq` 续传，状态回 running；
2. 机器上进程其实已经没了 → 重连对账后 Server 落 `exception`；
3. 机器长时间不回来 → 租约到期且对账确认进程不在后落 `exception`（阈值见 Server 配置
   `atest.dispatch.disconnected-timeout-sec`）。

处置动线：`GET /api/timeline?agentId=...` 看断连时间 → 登机器 `systemctl status atagent` →
网络/进程二选一处理。**在失联期间对该执行做"原地重跑"是被禁止的（非终态）**。

## 8. 取消与重跑语义

- **取消**：`POST /api/tasks/{id}/cancel`。pending 直接落 canceled；running 会给 Agent 发
  cancel（按 token 杀**进程组**），终态等 `fin` 落 canceled。Agent 不在线时由 Server 兜底落定。
- **重跑**：`POST /api/tasks/{id}/rerun`，body `{"mode":"inplace"}` 或 `{"mode":"new"}`，仅对终态执行生效：
  - `inplace` 原地重跑：**清空原日志**，同一条记录回 pending（页面上会看到旧日志消失，这是设计行为）；
  - `new` 新记录重跑：生成新的 execution，历史保留。
- 想保留现场对比 → 用 `new`；只想再试一次不留垃圾 → 用 `inplace`。

## 9. 常用命令备忘

```bash
# 建任务（operator 可空；targets 写 agentId 或 tag 都行）
curl -s -X POST http://127.0.0.1:8080/api/tasks -H 'Content-Type: application/json' -d '{
  "command": "echo hello && echo 0",
  "cwd": "/tmp",
  "env": {"FOO": "bar"},
  "targets": ["qa-node-01"],
  "timeoutSec": 600,
  "operator": "someone"
}'

# 取消 / 重跑 / 调序
curl -s -X POST http://127.0.0.1:8080/api/tasks/<taskId>/cancel
curl -s -X POST http://127.0.0.1:8080/api/tasks/<taskId>/rerun -H 'Content-Type: application/json' -d '{"mode":"new"}'

# 查执行与日志（注意 truncated 字段）
curl -s http://127.0.0.1:8080/api/executions/<executeId>
curl -s 'http://127.0.0.1:8080/api/executions/<executeId>/logs?from=0&limit=200'

# 实时日志 / 机器快照（SSE）
curl -N 'http://127.0.0.1:8080/api/sse/exec/<executeId>?from=0'
curl -N http://127.0.0.1:8080/api/sse/agents

# 机器管理
curl -s -X PATCH http://127.0.0.1:8080/api/agents/<agentId> -H 'Content-Type: application/json' -d '{"concurrency":2}'
curl -s -X POST  http://127.0.0.1:8080/api/agents/<agentId>/restart
curl -s -X POST  http://127.0.0.1:8080/api/agents/<agentId>/stop
```

开发库直查：浏览器打开 `http://127.0.0.1:8080/h2-console`，
JDBC URL `jdbc:h2:file:./data/atest`，用户 `sa`，空密码。

## 10. 升级 / 重启注意事项

- Server 单实例：重启期间 Agent 会断线重连，在跑的执行进入失联子状态，**不会丢终态**
  （Agent 的 `fin` 可靠重发直到 ACK）。低峰期操作即可。
- Agent 升级会杀掉在跑任务（systemd `KillMode=control-group`），机器空闲时再升，见
  [`deploy/README.md`](../deploy/README.md)。
- 生产安装、systemd、卸载等运维操作全部见 [`deploy/README.md`](../deploy/README.md)。

## 11. `docker compose` 在嵌套/沙箱环境起不来

| 现象 | 原因 | 处置 |
|---|---|---|
| pull 时报 `failed to convert whiteout file ... operation not permitted` | dockerd 走 containerd overlayfs snapshotter，沙箱里 `mknod` 被拒 | `/etc/docker/daemon.json` 设 `"storage-driver": "vfs"` 且 `"features": {"containerd-snapshotter": false}`，只重启本任务的 dockerd |
| 容器内 `npm install` / `apk` / `go` 一直挂、宿主机出网正常 | 旧 dockerd 留下的 **iptables-legacy** FORWARD 默认 DROP，新 dockerd 已改 nftables，compose 网桥流量被 legacy 表丢掉 | `iptables-legacy -P FORWARD ACCEPT`（过滤仍由当前 dockerd 的 nft 规则负责） |
| Agent 日志 `sh: -data-dir: not found` | compose 里 `command: >` 续行缩进多了，YAML 保留换行，shell 把参数拆成下一条命令 | 续行与 `sh -c` 同行同缩进，见 `deploy/docker-compose.yml` 注释 |
| 新 Agent 被拒 `tag_conflict` | 之前一次失败启动用同一 displayTag 注册过、data-dir 又没持久化 | `PATCH /api/agents/{id}` 把旧记录改成别的 tag，或清开发库 |

本仓库 compose 的 Server 镜像是 `eclipse-temurin:17-jre-jammy` + 预编译 jar，避免再拉带 hsperfdata whiteout 的 Maven 镜像。
