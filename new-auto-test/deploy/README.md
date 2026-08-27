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
- 只走内网。脚本**不依赖任何公网地址**：二进制用 `--bin` 指本地文件，或用 `--url` 指内网地址——包括 Server 自己（`http://<server>:8080/api/agent/binary`，见下方「在线安装」）。

运维台「机器列表」的「安装 Agent」抽屉提供三种方式（见下方「在线安装」）：复制命令、curl 一行安装、SSH 代装；前两种最终都是操作者在目标机上以 root 执行本目录的 `install.sh`，第三种由 Server 通过 SSH 上传并代跑同一份脚本。「重启 Agent」只对**在线**会话下发 `stop`，让本机 systemd 再拉起；离线行（例如误注册留下的 `stale-docker-agent-01`）点了会返回「agent 当前离线，无法重启」，也不会在任何物理机上装出新进程。

## 在线安装（运维台页面）

`#/agents`（机器列表）右上角「安装 Agent」抽屉，三种方式共用同一份 tag / Server 地址 / 并发，全部走本目录的 `install.sh`：

| 方式 | 谁执行 | 适用场景 |
|---|---|---|
| 复制命令 | 操作者在目标机 root 执行 | 目标机访问不了 Server :8080，或想全程手工 |
| curl 安装 | 操作者在目标机 root 执行一行命令 | 目标机能访问 Server 的 HTTP :8080（内网） |
| SSH 代装 | Server 通过 SSH 代跑 | 手边只有浏览器，目标机开着 sshd |

### curl 一行安装

```bash
curl -fsSL http://<server-host>:8080/api/agent/install.sh | sudo bash -s -- --tag qa-node-01 --server <server-host>:9800
```

`/api/agent/install.sh` 是 Server 动态生成的引导脚本：从**同一台 Server** 下载真正的
`install.sh`（`/api/agent/files/install.sh`）与 unit 模板（`/api/agent/files/atagent.service`），
然后以 `--url http://<server>:8080/api/agent/binary --sha256 <hex>` 执行，命令行参数原样透传
（`--concurrency`、`--data-dir`、`--new-agent-id` … 全都能带）。整条链路只在内网，不碰公网。

### Server 怎么拿到要分发的二进制（打包约定）

`install.sh` 与 `atagent.service` 在 `mvn package` 时从 `deploy/` 拷进 jar（`server/pom.xml`
的 resources 配置，classpath `agent-dist/`），与仓库永远一致、jar 天生自带。**二进制不进 git
也不进 jar**（平台产物），Server 运行时按顺序找：

1. `atest.agent-dist.dir` 配置的目录（默认 `./dist/agent`，相对 Server 工作目录）下的 `atagent`；
2. `../dist/agent/atagent`、`../agent/atagent`（源码仓库开发布局的兜底）。

填充方式：

```bash
cd deploy && make agent-dist     # 等价 cd agent && make static，产物写到 <仓库根>/dist/agent/atagent
```

- `make dev` 已包含该步骤；compose 里的 agent 容器构建完也会自动把静态产物拷进
  `../dist/agent`（挂载到 server 容器 `/app/dist/agent`），宿主机没有 go 也能就绪。
- 生产部署：在 Server 机器上 jar 同级建 `dist/agent/`，把 linux/amd64 **静态**编译的
  `atagent` 放进去（`CGO_ENABLED=0 GOOS=linux GOARCH=amd64`），或改
  `atest.agent-dist.dir` 指到现有分发目录。
- 目录里没有二进制时：页面会给出同样的指引，`/api/agent/install.sh`、`/api/agent/binary`
  返回 503（`binary_missing`），`curl -f` 会直接失败，不会把错误页当脚本执行。

`GET /api/agent/install-info` 返回当前分发状态（是否就绪、sha256、大小、是否 ELF）。

### SSH 代装

抽屉里的「SSH 代装」让 Server 直接 SSH 到目标机：SFTP 上传 `atagent` + `install.sh` +
`atagent.service` 到 `/tmp/atagent-ssh-install.<random>/`，执行
`sudo bash ./install.sh --server … --tag … --concurrency … --bin ./atagent`
（root 登录时不加 sudo），把输出尾部（默认 200 行）回显到页面，装完顺手删掉临时目录。

- **凭据不落地**：口令 / 私钥只在单次请求的内存里用，不写 H2、不进日志（`org.apache.sshd`
  日志压到 WARN，业务日志只记 host/tag/结果码）；远端命令行里也没有任何凭据。
- **主机指纹**：默认 accept-new——首次连接记录到 `atest.ssh-install.known-hosts-file`
  （默认 `./data/ssh-known-hosts`），之后指纹变了拒绝（`host_key_changed`）。页面可勾选
  「跳过主机指纹校验」（有醒目警告，仅限受控内网）。
- **超时**：连接 / 认证默认 10s，整个安装 180s（`atest.ssh-install.*` 可调），超时断开 SSH。
- **私钥格式**：OpenSSH / PEM，支持 ed25519（jar 里带了 `net.i2p.crypto:eddsa`）、RSA、ECDSA，
  加密私钥需附口令。
- 返回结构化 JSON：`ok` / `exitCode` / `output` / `error` / `errorCode`。`errorCode` 取值：
  `connect_failed`（连不上）、`auth_failed`、`bad_private_key`、`host_key_changed`、
  `upload_failed`、`timeout`、`tag_conflict`（Server 在安装窗口内看到该 tag 的注册被拒）、
  `install_failed`（其余非 0 退出，输出里带 `journalctl -u atagent` 尾部帮助定位）、
  `binary_missing`。

## 装 Agent

把 `deploy/` 整个目录（`install.sh` + `atagent.service`）和编译好的 `atagent` 拷到**目标 Linux 测试机**（需要 root + systemd），然后：

```bash
sudo ./install.sh --server 10.0.0.5:9800 --tag qa-node-01
```

不带 `--bin` 时，脚本按顺序找 `./atagent`（脚本同级）、当前目录的 `./atagent`、`../agent/atagent`。放在别处就显式指定：

```bash
sudo ./install.sh --server 10.0.0.5:9800 --tag qa-node-01 --bin /tmp/atagent
```

或者从内网文件服务拉（可选校验），Server 自己就是现成的文件服务：

```bash
sudo ./install.sh --server 10.0.0.5:9800 --tag qa-node-01 \
     --url http://10.0.0.5:8080/api/agent/binary --sha256 <hex>
```

### 参数

| 参数 | 说明 |
|---|---|
| `--server HOST:PORT` | Server 的 Agent TCP 地址，首次安装必填，之后可省略（沿用旧配置） |
| `--tag NAME` | 本机显示名，**全局唯一**，重名 Server 会拒绝。默认取 `hostname` |
| `--data-dir DIR` | 数据目录，默认 `/var/lib/atagent`；放 `agent-id`、执行日志尾部（`journal/`）、待确认结果（`spool/`） |
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
8. **等注册**：用 `atagent status -json` 轮询 `connected`（最多 20s）。`is-active` 只说明进程活着——tag 重名（`tag_conflict`）或 9800 不通时服务照样 active、一直重连，机器却永远不会在 机器列表 上线。连不上时脚本打出排查提示（journal、`nc -vz`、换 tag）并以非 0 退出；文件已落盘、服务会持续重试，修复原因后重跑脚本即可。

脚本可以重复执行，重装/升级不会换掉机器身份。

### 装完怎么验证

脚本最后一步自己会等 Agent 连上 Server，所以**脚本成功退出就代表机器已经注册**。手工复核：

```bash
atagent status                                        # connection 一行是 connected 才是真在线
atagent status -json | grep '"connected"'
curl -s http://<server-host>:8080/api/agents | grep <tag>
```

再到运维台 `#/agents`（机器列表）看：新机器一行显示 **在线**、空闲 `0/1`（并发默认 1）、版本号来自本次安装的二进制。显示 离线 或压根不出现，回 [`docs/runbook.md`](../docs/runbook.md) §4。

### 落盘位置

```
/usr/local/bin/atagent               二进制
/etc/atagent/config.yaml             配置
/var/lib/atagent/agent-id            机器身份 UUID，重装保留
/var/lib/atagent/journal/            每个执行的日志尾部（≤5MB）
/var/lib/atagent/spool/fin/          尚未被 Server 确认的结果帧
/etc/systemd/system/atagent.service  unit
```

Agent **自身**日志只进 journald（`journalctl -u atagent`），没有独立日志目录；旧版脚本创建过的 `/var/log/atagent/` 永远是空的，`--uninstall --purge` 会顺手清掉。

### config.yaml

```yaml
server: "10.0.0.5:9800"      # Server Agent TCP 端口
tag: "qa-node-01"            # 显示名，全局唯一
data_dir: "/var/lib/atagent" # agent-id、journal/、spool/ 都在这里
concurrency: 1               # 1-4，只有空闲时改才生效
heartbeat_sec: 5             # 心跳，Server 按此续租约
max_log_bytes: 5242880       # 单次执行 5MB 上限，超出保留尾部并标记截断
log_level: "info"
```

这里只有二进制真正会读的键（完整清单与可选键 `shell` / `kill_grace_sec` / `kill_on_shutdown` / `aliases` / `env` 见 [`agent/README.md`](../agent/README.md)「配置」）。旧版脚本写过的 `log_dir`、`agent_id_file`、`work_dir`、`reconnect_min_ms`、`reconnect_max_ms`、`log_batch_ms` 都是 Agent 不认识的键，写了也不生效：机器身份固定读 `<data_dir>/agent-id`，重连退避内置 500ms 起、30s 封顶，日志固定 200ms 一批上送。

手工改完配置要 `systemctl restart atagent`。`concurrency` 也可以在运维台上通过 `PATCH /api/agents/{agentId}` 改，机器空闲时才允许。

### 对 `atagent` 二进制的约定

unit 里是 `atagent run --config /etc/atagent/config.yaml`，并导出 `ATEST_CONFIG`（Agent 唯一认的配置环境变量，没有 `ATAGENT_CONFIG` 之类的别名）：

- 接受 `run --config <path>`（Go flag，`-config` 等价；不写子命令时默认就是 `run`），没有该参数时读 `ATEST_CONFIG`；
- 配置字段名与上面那份 `config.yaml` 一致；未知键会被忽略；
- 机器身份是 `<data_dir>/agent-id`，安装时生成，Agent 沿用；
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
