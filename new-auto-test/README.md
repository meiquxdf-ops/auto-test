# new-auto-test

分布式测试执行平台（全新实现）。代码全部在本目录，与仓库内旧的 `auto-test-*` 模块无关，协议不兼容旧 Hessian / 旧 Java Agent。

- **Server**（Java 17 / Spring Boot）：HTTP API `:8080` + Agent 长度前缀 TCP `:9800`，单实例
- **Agent**（Go 单二进制 `atagent`）：装在每台测试机上，`bash -c` 执行下发命令并回传日志
- **Frontend**（Vue 3 + Element Plus + Vite）：中文运维台
- 第一期无登录（内网使用）

```
new-auto-test/
├── docs/
│   ├── protocol.md    协议与产品规格（冻结，一切语义以此为准）
│   └── runbook.md     排障动线
├── server/            Java Server（Maven 工程）
├── agent/             Go Agent（cmd/atagent 单二进制）
├── frontend/          Vue 3 前端（Vite 工程）
└── deploy/            docker-compose + Makefile + 生产安装脚本与 systemd
```

## 环境要求

| 组件 | 版本 | 检查命令 |
|---|---|---|
| Java（Server） | **17+** | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Go（Agent） | **1.22+** | `go version` |
| Node（前端） | **18+**（含 npm 9+） | `node -v` |
| Docker + Compose（可选，一键开发用） | v2 | `docker compose version` |

三个默认端口：`8080`（HTTP API/SSE）、`9800`（Agent TCP）、`5173`（前端 dev server）。

## 快速开始

### 方式一：Docker 一键起（推荐）

Compose 会起 Server、前端，以及一台 tag=`docker-agent-01` 的验收 Agent（真实 bash）。

```bash
cd new-auto-test/deploy
make dev          # 先打包 jar，再起 Server(:8080/:9800) + 前端(:5173) + Agent
```

再挂一台宿主机 Agent（可选）：

```bash
cd new-auto-test/deploy
make agent        # 编译 agent/atagent 并以 tag=dev-<hostname> 连到 127.0.0.1:9800
# 自定义：make agent TAG=qa-node-01 CONCURRENCY=2
```

打开 <http://127.0.0.1:5173>，在「机器列表」应能看到刚接入的机器，去「测试下发页」发一条
`echo hello && echo 0`，跟着实时日志看到 `pass` 即链路全通。

### 方式二：本地三个终端（不用 Docker）

```bash
# 终端 1：Server（首次会拉 Maven 依赖，耐心）
cd new-auto-test/server
mvn spring-boot:run
# 默认用内嵌 H2 文件库（server/data/），免装数据库；
# 生产 MySQL：mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=mysql

# 终端 2：Agent
cd new-auto-test/agent
go build -o atagent ./cmd/atagent
mkdir -p data
./atagent run -server 127.0.0.1:9800 -tag dev-node -data-dir ./data
# 更多参数见 ./atagent run -h 与 agent/README.md；生产安装（systemd）见 deploy/README.md

# 终端 3：前端
cd new-auto-test/frontend
npm install
npm run dev
# /api 已反代到 http://127.0.0.1:8080（可用 VITE_API_BASE 覆盖）
```

打开 <http://127.0.0.1:5173>。

### 验证一条最小链路（纯命令行）

```bash
# 机器注册上来了吗
curl -s http://127.0.0.1:8080/api/agents

# 用 tag 下发一条命令（operator 可空）
curl -s -X POST http://127.0.0.1:8080/api/tasks -H 'Content-Type: application/json' \
  -d '{"command":"echo hello && echo 0","targets":["dev-node"]}'

# 用返回里的 executeId 看状态与日志
curl -s http://127.0.0.1:8080/api/executions/<executeId>
curl -s 'http://127.0.0.1:8080/api/executions/<executeId>/logs?from=0&limit=100'
```

## 构建发布产物

```bash
cd new-auto-test/deploy
make build        # = Server jar + agent/atagent 二进制 + frontend/dist
make test         # Server mvn test + Agent go test ./...
```

- Server jar：`server/target/*.jar`，`java -jar` 直接跑
- Agent：`agent/atagent` 静态二进制，拷到测试机用 `deploy/install.sh` 安装（systemd 托管）
- 前端：`frontend/dist/` 静态文件，交给任意静态服务器/网关，`/api` 转发到 Server 8080

生产部署（安装脚本、systemd、升级/卸载）见 [`deploy/README.md`](deploy/README.md)。

## 产品语义速览

完整规格见 [`docs/protocol.md`](docs/protocol.md)（冻结），这里只列日常用得到的：

- **身份**：`agentId` 安装时生成、落盘保留；**一机一 tag**，全局唯一、重名拒绝，tag 与 agentId 可互相解析。
- **建任务**：HTTP `POST /api/tasks`，字段 `command + cwd + env + targets + conditionConfig + timeoutSec`，`operator` 可空；前端有专门的「测试下发页」，一键下发并跟日志。
- **状态机**：`pending → dispatching → running → pass|fail|block|exception`，四种测试结果；
  **canceled 单独一类**（用户取消）；**失联是 running 的子状态，不是终态**。
- **判定**：看最后一行 stdout，`equals/not-equals/include/regex` 先匹配先赢，`other` 兜底；
  无规则时 `exitCode==0 → pass`。
- **重跑**：`inplace` 原地重跑（**清空旧日志**、同一条记录）或 `new` 新记录重跑（历史保留）。
- **队列**：只能调 **pending** 的顺序，不抢占在跑的；不自动重试。
- **并发**：每机默认 1、最大 4，仅空闲时可改。
- **日志**：每次执行上限 **5MB（保留尾部）**，超出即截断，API/SSE/页面都会明确标出。

## 出问题了？

按 [`docs/runbook.md`](docs/runbook.md) 的排障动线走：起不来、连不上、卡 pending、
失联、日志截断、结果不符……都有对应小节和现成命令。
