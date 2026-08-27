# 部署 new-auto-test

本目录只放安装脚本和 systemd 模板，不含源码。

| 文件 | 用途 |
|---|---|
| `install.sh` | 在测试机上安装 / 升级 / 卸载 Go Agent（`atagent`） |
| `atagent.service` | Agent 的 systemd unit 模板，`install.sh` 渲染后写到 `/etc/systemd/system/` |
| `server.service` | Server（Spring Boot jar）的 systemd unit 模板，可选，手工渲染 |

## 使用者与前提

- **测试同学**给自己的测试机装 Agent，**运维**批量装 Agent 和那台唯一的 Server，两边用的是同一个 `install.sh`。
- 测试机上大家都有 **root**（`sudo -i` 或直接 root 登录）。脚本第一件事就是检查 uid，不是 root 直接退出。
- Agent 以 **root 运行**：被测脚本要装包、改系统、读别人家目录，跑在受限用户下会莫名其妙失败。unit 里没有加任何沙箱选项，就是这个原因。
- 只走内网。脚本**不依赖任何公网地址**，没有 `curl … | sh` 那种写法：二进制要么用 `--bin` 指本地文件，要么用 `--url` 指内网自己的文件服务。

运维台「机器列表」**不能安装** Agent：那一页只展示已经 `hello` 过的身份。「重启 Agent」只对**在线**会话下发 `stop`，让本机 systemd 再拉起；离线行（例如误注册留下的 `stale-docker-agent-01`）点了会返回「agent 当前离线，无法重启」，也不会在任何物理机上装出新进程。

## 装 Agent

把 `deploy/` 整个目录（`install.sh` + `atagent.service`）和编译好的 `atagent` 拷到**目标 Linux 测试机**（需要 root + systemd），然后：

```bash
sudo ./install.sh --server 10.0.0.5:9800 --tag qa-node-01
```

不带 `--bin` 时，脚本按顺序找 `./atagent`（脚本同级）、当前目录的 `./atagent`、`../agent/atagent`。放在别处就显式指定：

```bash
sudo ./install.sh --server 10.0.0.5:9800 --tag qa-node-01 --bin /tmp/atagent
```

或者从内网文件服务拉（可选校验）：

```bash
sudo ./install.sh --server 10.0.0.5:9800 --tag qa-node-01 \
     --url http://10.0.0.5:8888/atagent --sha256 <hex>
```

### 参数

| 参数 | 说明 |
|---|---|
| `--server HOST:PORT` | Server 的 Agent TCP 地址，首次安装必填，之后可省略（沿用旧配置） |
| `--tag NAME` | 本机显示名，**全局唯一**，重名 Server 会拒绝。默认取 `hostname` |
| `--data-dir DIR` | 数据目录，默认 `/var/lib/atagent`；放 `agent-id` 和执行工作区 |
| `--log-dir DIR` | Agent 自身日志目录，默认 `/var/log/atagent` |
| `--bin PATH` | 用本地二进制 |
| `--url URL` | 从内网 URL 下载二进制（与 `--bin` 互斥） |
| `--sha256 HEX` | 配合 `--url` 校验 |
| `--concurrency N` | 本机最大并发任务数，1–4，默认 1 |
| `--new-agent-id` | 重新生成 `agent-id`（默认保留） |
| `--no-enable` | 只落盘、不 `enable --now`，做基础镜像时用 |
| `--uninstall` / `--purge` | 卸载 / 连配置数据一起删 |

### 脚本做了什么

1. 校验 root、参数格式（`host:port`、tag 字符集、并发 1–4）。
2. 取二进制到临时目录，可选 sha256 校验，顺手看一眼是不是 ELF 文件（防止把 HTML 错误页装上去）。
3. **先停旧的**：`systemctl stop atagent`，再扫一遍残留 `atagent` 进程，SIGTERM → 等 10s → SIGKILL。旧进程不停干净会和新进程抢同一个 `agentId`，Server 那边表现为 `dup_session`。
4. 安装二进制到 `/usr/local/bin/atagent`（先写 `.new` 再 `mv`，避免 ETXTBSY）。
5. 生成 `agent-id`：`$DATA_DIR/agent-id` 存在就沿用，不存在才生成 UUID（`/proc/sys/kernel/random/uuid` → `uuidgen` → `/dev/urandom` 兜底）。
6. 写 `/etc/atagent/config.yaml`，旧文件备份成 `config.yaml.bak.<时间戳>`。
7. 用 `atagent.service` 模板渲染 `/etc/systemd/system/atagent.service`，`daemon-reload` + `enable --now`，再确认 `is-active`，起不来直接打 `status` 并以非 0 退出。

脚本可以重复执行，重装/升级不会换掉机器身份。

### 落盘位置

```
/usr/local/bin/atagent               二进制
/etc/atagent/config.yaml             配置
/var/lib/atagent/agent-id            机器身份 UUID，重装保留
/var/lib/atagent/work/               任务默认工作目录
/var/log/atagent/                    Agent 自身日志
/etc/systemd/system/atagent.service  unit
```

### config.yaml

```yaml
server: "10.0.0.5:9800"      # Server Agent TCP 端口
tag: "qa-node-01"            # 显示名，全局唯一
data_dir: "/var/lib/atagent"
log_dir: "/var/log/atagent"
agent_id_file: "/var/lib/atagent/agent-id"
work_dir: "/var/lib/atagent/work"
concurrency: 1               # 1-4，只有空闲时改才生效
heartbeat_sec: 5             # 心跳，Server 按此续租约
reconnect_min_ms: 500
reconnect_max_ms: 15000
max_log_bytes: 5242880       # 单次执行 5MB 上限，超出保留尾部并标记截断
log_batch_ms: 200
log_level: "info"
```

手工改完配置要 `systemctl restart atagent`。`concurrency` 也可以在运维台上通过 `PATCH /api/agents/{agentId}` 改，机器空闲时才允许。

### 对 `atagent` 二进制的约定

unit 里是 `atagent --config /etc/atagent/config.yaml`，并导出 `ATEST_CONFIG`（Agent 实际读取的环境变量）：

- 接受 `--config <path>`（Go flag，`-config` 等价），或在没有该参数时读 `ATEST_CONFIG`；
- 配置字段名与上面那份 `config.yaml` 一致；
- `agent_id_file` 里的 UUID 就是 `agentId`，Agent 只读不写（安装脚本负责生成）；
- 收到 `SIGTERM` 时优雅退出。父进程被 systemd 以 `control-group` 方式回收，任务派生的子进程会一起被清掉，所以 Agent 自己不用兜底杀子进程树；
- 前台运行，不要自己 daemonize（`Type=simple`）。

## 升级

```bash
sudo ./install.sh --bin /tmp/atagent-new
```

不传 `--server` / `--tag` 就沿用现有配置。注意升级会停服务，**正在跑的任务会被杀**（unit 是 `KillMode=control-group`，整个进程组一起回收），机器空闲时再升。

## 卸载

```bash
sudo ./install.sh --uninstall          # 保留 /etc/atagent 和数据，重装后 agent-id 不变
sudo ./install.sh --uninstall --purge  # 配置、数据、日志一并删除
```

## 排查

```bash
systemctl status atagent
journalctl -u atagent -n 200 --no-pager
journalctl -u atagent -f
cat /var/lib/atagent/agent-id
```

常见问题：

- **连不上 Server**：在测试机上 `nc -vz <server-host> 9800` 试一下，多半是防火墙或安全组没放 9800。
- **机器在列表里重复出现**：`--new-agent-id` 换过身份，或者机器是克隆出来的、`/var/lib/atagent/agent-id` 跟着镜像复制了。克隆机第一次装要加 `--new-agent-id`。
- **tag 重名被拒**：换一个 `--tag` 重跑脚本。
- **服务反复重启**：`Restart=always` 会一直拉起，看 journal 里的启动报错，通常是 `config.yaml` 写错或 `server` 地址不通。

## 装 Server（可选）

`server.service` 是模板，需要替换 `@USER@`、`@HOME@`、`@JAVA@`、`@JAR@` 四个占位符。Server 单实例，通常运维装在一台固定机器上：

```bash
sudo useradd -r -m -d /opt/atserver atserver
sudo install -d -m 0755 /etc/atserver
sudo install -o atserver -g atserver -m 0644 target/atest-server.jar /opt/atserver/atserver.jar

sudo sed -e "s#@USER@#atserver#g" \
         -e "s#@HOME@#/opt/atserver#g" \
         -e "s#@JAVA@#$(command -v java)#g" \
         -e "s#@JAR@#/opt/atserver/atserver.jar#g" \
         server.service > /etc/systemd/system/atserver.service

sudo systemctl daemon-reload && sudo systemctl enable --now atserver
```

JVM 参数、数据库地址之类放 `/etc/atserver/server.env`（unit 里 `EnvironmentFile=-`，文件不存在也不报错）：

```
JAVA_OPTS=-Xms1g -Xmx4g -XX:+UseG1GC
SPRING_PROFILES_ACTIVE=prod
```

Spring 的额外配置从 `/etc/atserver/` 读（`--spring.config.additional-location`），放 `application.yml` 覆盖默认值即可。端口：HTTP `8080`、Agent TCP `9800`，两个都要对内网放通。
