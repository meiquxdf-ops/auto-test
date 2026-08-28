#!/usr/bin/env bash
#
# new-auto-test Agent 安装脚本（需要 root）
#
#   ./install.sh --server 10.0.0.5:9800 --tag qa-node-01
#   ./install.sh --server 10.0.0.5:9800 --tag qa-node-01 --bin /tmp/atagent
#   ./install.sh --uninstall
#
# 详见同目录 README.md。
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

SERVICE_NAME="atagent"
CONF_DIR="/etc/atagent"
CONF_FILE="${CONF_DIR}/config.yaml"
INSTALL_BIN="/usr/local/bin/atagent"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
DEFAULT_DATA_DIR="/var/lib/atagent"
# 旧版脚本写过的 Agent 日志目录；二进制从不往这里写（日志走 journald），仅卸载 --purge 时清理
LEGACY_LOG_DIR="/var/log/atagent"

SERVER=""
TAG=""
DATA_DIR=""
BIN_SRC=""
BIN_URL=""
BIN_SHA256=""
CONCURRENCY=""
NEW_AGENT_ID=0
DO_ENABLE=1
DO_UNINSTALL=0
DO_PURGE=0

log()  { printf '[atagent] %s\n' "$*"; }
warn() { printf '[atagent] 警告: %s\n' "$*" >&2; }
die()  { printf '[atagent] 错误: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
用法: install.sh [选项]

  --server HOST:PORT   Server 的 Agent TCP 地址，例如 10.0.0.5:9800（首次安装必填）
  --tag NAME           本机显示名，全局唯一；默认取 hostname
  --data-dir DIR       数据目录，默认 /var/lib/atagent（agent-id、执行日志尾部、待确认结果）
  --bin PATH           使用本地二进制（默认自动在脚本同级、当前目录、../agent/ 下找 atagent）
  --url URL            从内网 URL 下载二进制（与 --bin 二选一）
  --sha256 HEX         配合 --url 校验下载结果
  --concurrency N      本机最大并发任务数 1-4，默认 1
  --new-agent-id       重新生成 agent-id（默认保留旧的，机器身份不变）
  --no-enable          只落盘文件，不 enable/start（做基础镜像时用）
  --uninstall          停止并卸载服务
  --purge              配合 --uninstall，连配置和数据目录一起删
  -h, --help           显示本帮助

例子:
  sudo ./install.sh --server 10.0.0.5:9800 --tag qa-node-01
  sudo ./install.sh --server 10.0.0.5:9800 --tag qa-node-01 --bin ./atagent --concurrency 2
  sudo ./install.sh --bin ./atagent            # 升级，沿用已有配置
  sudo ./install.sh --uninstall --purge
EOF
}

# ---------------------------------------------------------------- 参数解析

need_value() {
    [[ $# -ge 2 && -n "${2:-}" ]] || die "$1 需要一个参数值"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --server)       need_value "$@"; SERVER="$2"; shift 2 ;;
        --tag)          need_value "$@"; TAG="$2"; shift 2 ;;
        --data-dir)     need_value "$@"; DATA_DIR="$2"; shift 2 ;;
        # 旧参数：二进制没有独立日志目录（自身日志只写 journald），接受但忽略
        --log-dir)      need_value "$@"; warn "--log-dir 已废弃并被忽略：Agent 日志看 journalctl -u ${SERVICE_NAME}"; shift 2 ;;
        --bin)          need_value "$@"; BIN_SRC="$2"; shift 2 ;;
        --url)          need_value "$@"; BIN_URL="$2"; shift 2 ;;
        --sha256)       need_value "$@"; BIN_SHA256="$2"; shift 2 ;;
        --concurrency)  need_value "$@"; CONCURRENCY="$2"; shift 2 ;;
        --new-agent-id) NEW_AGENT_ID=1; shift ;;
        --no-enable)    DO_ENABLE=0; shift ;;
        --uninstall)    DO_UNINSTALL=1; shift ;;
        --purge)        DO_PURGE=1; shift ;;
        -h|--help)      usage; exit 0 ;;
        *)              usage >&2; die "未知参数: $1" ;;
    esac
done

[[ "$(id -u)" == "0" ]] || die "必须用 root 运行（sudo ./install.sh ...）"

# ---------------------------------------------------------------- systemd helpers

have_systemd() { command -v systemctl >/dev/null 2>&1 && [[ -d /run/systemd/system ]]; }

# 找还活着的、属于本机安装的 atagent 进程。两层过滤：
# 1) 进程名精确匹配 atagent。不能用 pgrep -f：本脚本自己的命令行里就带 atagent
#    路径（--bin），全命令行匹配会把 sudo/自己一起杀掉；
# 2) 真实可执行文件必须是 ${INSTALL_BIN}（按 /proc/<pid>/exe 判定）。只看进程名会
#    误杀跑在其他路径的同名进程（compose/开发环境的 /tmp/atagent、./atagent 等），
#    它们有自己的 agent-id，不会和本机安装抢会话，轮不到本脚本收割。
find_agent_pids() {
    local out
    if command -v pgrep >/dev/null 2>&1; then
        out="$(pgrep -x atagent 2>/dev/null || true)"
    else
        out="$(ps -eo pid=,comm= 2>/dev/null | awk '$2 == "atagent" {print $1}' || true)"
    fi
    local pid exe
    for pid in $out; do
        [[ "$pid" == "$$" || "$pid" == "$PPID" ]] && continue
        exe="$(readlink -f "/proc/${pid}/exe" 2>/dev/null || true)"
        # 旧二进制被覆盖/删除后 exe 带 " (deleted)" 后缀，仍算本机安装的 Agent
        exe="${exe% (deleted)}"
        [[ "$exe" == "$INSTALL_BIN" ]] || continue
        printf '%s\n' "$pid"
    done
}

# 停掉可能还在跑的旧 Agent：先 systemd，再兜底扫残留进程。
stop_running_agent() {
    if have_systemd && systemctl is-active --quiet "${SERVICE_NAME}.service" 2>/dev/null; then
        log "停止旧服务 ${SERVICE_NAME}.service"
        systemctl stop "${SERVICE_NAME}.service" || warn "systemctl stop 失败，继续用信号兜底"
    fi

    # 非 systemd 拉起来的（手工 nohup、旧版脚本）也要收掉，
    # 否则新旧两个进程会用同一个 agentId 抢连接，触发 dup_session。
    # find_agent_pids 只会命中 ${INSTALL_BIN} 的进程，别的路径的同名进程不受影响。
    local pids
    pids="$(find_agent_pids | tr '\n' ' ')"
    [[ -n "${pids// /}" ]] || return 0

    log "发现残留的本机安装 atagent 进程（${INSTALL_BIN}）: ${pids}发送 SIGTERM"
    # shellcheck disable=SC2086
    kill -TERM $pids 2>/dev/null || true

    local i
    for i in $(seq 1 20); do
        pids="$(find_agent_pids | tr '\n' ' ')"
        [[ -n "${pids// /}" ]] || return 0
        sleep 0.5
    done

    warn "10s 内未退出，SIGKILL: ${pids}"
    # shellcheck disable=SC2086
    kill -KILL $pids 2>/dev/null || true
    sleep 0.5
}

# ---------------------------------------------------------------- 卸载

if [[ "$DO_UNINSTALL" == "1" ]]; then
    if have_systemd; then
        systemctl disable --now "${SERVICE_NAME}.service" >/dev/null 2>&1 || true
    fi
    stop_running_agent
    rm -f "$UNIT_FILE"
    if have_systemd; then
        systemctl daemon-reload || true
        systemctl reset-failed "${SERVICE_NAME}.service" >/dev/null 2>&1 || true
    fi
    rm -f "$INSTALL_BIN"
    if [[ "$DO_PURGE" == "1" ]]; then
        # data_dir 可能被 --data-dir 改过，按配置里的实际值删；
        # log_dir 是旧版脚本遗留键（二进制从不读），有就一并清掉
        purge_read() {
            sed -n "s/^$1:[[:space:]]*\"\{0,1\}\([^\"]*\)\"\{0,1\}[[:space:]]*$/\1/p" "$CONF_FILE" 2>/dev/null | tail -1
        }
        purge_data="$(purge_read data_dir)"
        purge_log="$(purge_read log_dir)"
        rm -rf "$CONF_DIR" "${purge_data:-$DEFAULT_DATA_DIR}" "${purge_log:-$LEGACY_LOG_DIR}"
        log "已卸载并清除配置与数据"
    else
        log "已卸载（保留 ${CONF_DIR} 与数据目录，重装后 agent-id 不变）"
    fi
    exit 0
fi

# ---------------------------------------------------------------- 读取旧配置（升级场景可以不传参）

conf_get() {
    [[ -f "$CONF_FILE" ]] || return 0
    sed -n "s/^$1:[[:space:]]*\"\{0,1\}\([^\"]*\)\"\{0,1\}[[:space:]]*$/\1/p" "$CONF_FILE" | tail -1
}

OLD_SERVER="$(conf_get server)"
OLD_TAG="$(conf_get tag)"
OLD_DATA_DIR="$(conf_get data_dir)"
OLD_CONCURRENCY="$(conf_get concurrency)"

SERVER="${SERVER:-$OLD_SERVER}"
TAG="${TAG:-$OLD_TAG}"
DATA_DIR="${DATA_DIR:-${OLD_DATA_DIR:-$DEFAULT_DATA_DIR}}"
CONCURRENCY="${CONCURRENCY:-${OLD_CONCURRENCY:-1}}"
TAG="${TAG:-$(hostname -s 2>/dev/null || hostname)}"

# ---------------------------------------------------------------- 参数校验

[[ -n "$SERVER" ]] || die "缺少 --server，格式 host:9800"
[[ "$SERVER" =~ ^[A-Za-z0-9._-]+:[0-9]{1,5}$ ]] || die "--server 格式应为 host:port，当前: $SERVER"
SERVER_PORT="${SERVER##*:}"
(( 10#$SERVER_PORT >= 1 && 10#$SERVER_PORT <= 65535 )) || die "--server 端口非法: $SERVER_PORT"

[[ "$TAG" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die "--tag 只允许字母数字和 . _ -，长度 1-64，当前: $TAG"
# 协议规定 displayTag 全局唯一，重名会被 Server 拒绝
[[ "$CONCURRENCY" =~ ^[1-4]$ ]] || die "--concurrency 只能是 1-4，当前: $CONCURRENCY"
[[ "$DATA_DIR" == /* ]] || die "--data-dir 必须是绝对路径"
[[ -z "$BIN_SRC" || -z "$BIN_URL" ]] || die "--bin 与 --url 只能选一个"

# ---------------------------------------------------------------- 取二进制

TMP_DIR="$(mktemp -d /tmp/atagent-install.XXXXXX)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

STAGED_BIN=""

if [[ -n "$BIN_URL" ]]; then
    log "下载二进制: $BIN_URL"
    STAGED_BIN="${TMP_DIR}/atagent"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --connect-timeout 10 --retry 3 -o "$STAGED_BIN" "$BIN_URL" \
            || die "下载失败: $BIN_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --timeout=30 --tries=3 -O "$STAGED_BIN" "$BIN_URL" \
            || die "下载失败: $BIN_URL"
    else
        die "机器上既没有 curl 也没有 wget，请改用 --bin 传本地二进制"
    fi
else
    if [[ -z "$BIN_SRC" ]]; then
        for cand in "${SCRIPT_DIR}/atagent" "./atagent" "${SCRIPT_DIR}/../agent/atagent"; do
            if [[ -f "$cand" ]]; then BIN_SRC="$cand"; break; fi
        done
    fi
    [[ -n "$BIN_SRC" ]] || die "找不到 atagent 二进制。用 --bin /path/to/atagent 指定，或把它放在脚本同目录"
    [[ -f "$BIN_SRC" ]] || die "--bin 指向的文件不存在: $BIN_SRC"
    STAGED_BIN="${TMP_DIR}/atagent"
    cp -f "$BIN_SRC" "$STAGED_BIN" || die "复制二进制失败: $BIN_SRC"
    log "使用本地二进制: $BIN_SRC"
fi

[[ -s "$STAGED_BIN" ]] || die "二进制是空文件: ${BIN_SRC:-$BIN_URL}"

if [[ -n "$BIN_SHA256" ]]; then
    command -v sha256sum >/dev/null 2>&1 || die "没有 sha256sum，无法校验 --sha256"
    actual="$(sha256sum "$STAGED_BIN" | awk '{print $1}')"
    [[ "$actual" == "$BIN_SHA256" ]] || die "sha256 不匹配: 期望 $BIN_SHA256，实际 $actual"
    log "sha256 校验通过"
fi

# 粗判一下是不是 Linux 可执行文件，避免把 tar 包或 HTML 错误页装上去
if [[ "$(head -c 4 "$STAGED_BIN" | od -An -tx1 | tr -d ' \n')" != "7f454c46" ]]; then
    warn "${BIN_SRC:-$BIN_URL} 看着不像 ELF 可执行文件，请确认是 linux/amd64 编译产物"
fi
chmod 0755 "$STAGED_BIN"

# ---------------------------------------------------------------- 停旧进程

stop_running_agent

# ---------------------------------------------------------------- 落盘

install -d -m 0755 "$CONF_DIR" "$DATA_DIR"

# 原子替换，避免覆盖正在执行的文件时 ETXTBSY
cp -f "$STAGED_BIN" "${INSTALL_BIN}.new"
chmod 0755 "${INSTALL_BIN}.new"
mv -f "${INSTALL_BIN}.new" "$INSTALL_BIN"
log "二进制已安装: $INSTALL_BIN"

# agent-id：机器身份，重装不变
AGENT_ID_FILE="${DATA_DIR}/agent-id"
gen_uuid() {
    if [[ -r /proc/sys/kernel/random/uuid ]]; then
        cat /proc/sys/kernel/random/uuid
    elif command -v uuidgen >/dev/null 2>&1; then
        uuidgen | tr 'A-Z' 'a-z'
    else
        # 兜底：用内核随机数拼一个 v4 UUID
        od -An -tx1 -N16 /dev/urandom | tr -d ' \n' | awk '{
            printf "%s-%s-4%s-a%s-%s\n", substr($0,1,8), substr($0,9,4),
                   substr($0,14,3), substr($0,18,3), substr($0,21,12)
        }'
    fi
}

# 换了 --data-dir 的话把身份带过去，否则 Server 上会多出一台"新机器"
if [[ "$NEW_AGENT_ID" != "1" && -n "$OLD_DATA_DIR" && "$OLD_DATA_DIR" != "$DATA_DIR" \
      && ! -s "$AGENT_ID_FILE" && -s "${OLD_DATA_DIR}/agent-id" ]]; then
    cp -f "${OLD_DATA_DIR}/agent-id" "$AGENT_ID_FILE"
    log "数据目录 ${OLD_DATA_DIR} -> ${DATA_DIR}，已沿用原 agent-id（旧目录未删除）"
fi

if [[ "$NEW_AGENT_ID" == "1" && -f "$AGENT_ID_FILE" ]]; then
    mv -f "$AGENT_ID_FILE" "${AGENT_ID_FILE}.old.$(date +%Y%m%d%H%M%S)"
    warn "已按 --new-agent-id 重置身份，Server 上会出现一台新机器，旧记录需要手工清理"
fi

if [[ -s "$AGENT_ID_FILE" ]]; then
    AGENT_ID="$(tr -d '[:space:]' < "$AGENT_ID_FILE")"
    log "沿用已有 agent-id: $AGENT_ID"
else
    AGENT_ID="$(gen_uuid)"
    printf '%s\n' "$AGENT_ID" > "$AGENT_ID_FILE"
    chmod 0644 "$AGENT_ID_FILE"
    log "生成 agent-id: $AGENT_ID"
fi

# 配置文件
if [[ -f "$CONF_FILE" ]]; then
    cp -f "$CONF_FILE" "${CONF_FILE}.bak.$(date +%Y%m%d%H%M%S)"
fi

cat > "${CONF_FILE}.new" <<EOF
# atagent 配置，由 deploy/install.sh 生成于 $(date '+%Y-%m-%d %H:%M:%S')
# 改完执行: systemctl restart atagent
#
# 这里只写二进制真正会读的键（完整清单见 agent/README.md「配置」）。
# 机器身份固定读 <data_dir>/agent-id，不是配置项；
# 重连退避（500ms 起、30s 封顶）与日志批量节奏（200ms）内置，不可配。

# Server 的 Agent TCP 端口
server: "${SERVER}"
# 本机显示名，全局唯一，与 agentId 互相解析
tag: "${TAG}"

# 数据目录：agent-id、journal/（执行日志尾部）、spool/fin/（待确认结果）
data_dir: "${DATA_DIR}"

# 本机最大并发任务数，1-4；只有空闲时改才生效
concurrency: ${CONCURRENCY}

# 心跳间隔（秒），Server 按此续租约
heartbeat_sec: 5
# 单次执行日志上限 5MB，超出保留尾部并上报截断标记
max_log_bytes: 5242880
log_level: "info"
# 可选键 shell / kill_grace_sec / kill_on_shutdown / aliases / env
# 见 agent/config.example.yaml，按需追加
EOF
mv -f "${CONF_FILE}.new" "$CONF_FILE"
chmod 0644 "$CONF_FILE"
log "配置已写入: $CONF_FILE"

# ---------------------------------------------------------------- systemd unit

render_unit() {
    local tpl="${SCRIPT_DIR}/atagent.service"
    [[ -f "$tpl" ]] || die "找不到 unit 模板: $tpl（请连同 deploy/ 目录一起拷贝到目标机）"
    # 容器 / WSL / 精简系统可能没有 /etc/systemd/system；
    # set -e 下重定向到不存在的目录会把已装好二进制和配置的安装打断
    install -d -m 0755 "$(dirname "$UNIT_FILE")"
    sed -e "s#@BIN@#${INSTALL_BIN}#g" \
        -e "s#@CONFIG@#${CONF_FILE}#g" \
        -e "s#@DATA_DIR@#${DATA_DIR}#g" \
        "$tpl" > "${UNIT_FILE}.new"
    mv -f "${UNIT_FILE}.new" "$UNIT_FILE"
    chmod 0644 "$UNIT_FILE"
}

# unit 永远落盘：开机自启是安装的一部分，不随宿主环境静默降级。
# 镜像构建等场景里 systemd 此刻没在跑也没关系，文件写好了，装出来的机器开机就有服务。
render_unit
log "systemd unit 已写入: $UNIT_FILE"

# is-active 只说明进程活着。tag 重名（tag_conflict）或 9800 不通时，
# Agent 会一直重连、服务照样 active，但机器永远不会在 机器列表 里上线。
# 所以安装成功的标准是 atagent status 报告 connected，而不是 is-active。
VERIFY_WAIT_SEC=20

verify_registered() {
    local i out
    for i in $(seq 1 "$VERIFY_WAIT_SEC"); do
        out="$("$INSTALL_BIN" status -config "$CONF_FILE" -json 2>/dev/null || true)"
        if [[ "$out" == *'"connected": true'* || "$out" == *'"connected":true'* ]]; then
            log "已连上 Server（${SERVER}），机器列表(#/agents)中 ${TAG} 应显示 在线"
            return 0
        fi
        sleep 1
    done
    warn "服务在跑，但 ${VERIFY_WAIT_SEC}s 内没有连上 Server —— 机器不会出现在 机器列表，或一直显示 离线"
    warn "先看日志: journalctl -u ${SERVICE_NAME} -n 50 --no-pager"
    warn "  - tag_conflict（--tag 与已有机器重名）: 换一个 --tag 重跑本脚本"
    warn "  - 连不上: nc -vz ${SERVER%:*} ${SERVER_PORT}（防火墙/安全组要放 ${SERVER_PORT}）"
    die "注册验证失败。文件已落盘、服务会持续重试，修复原因后重跑本脚本即可"
}

if [[ "$DO_ENABLE" == "0" ]]; then
    log "按 --no-enable 跳过 enable/start（仅用于做基础镜像；正式安装不要带这个参数）"
else
    # 开机自启（systemctl enable）必须成功，否则安装就是失败的，不能静默放过
    have_systemd || die "开机自启需要 systemd，当前系统没有运行 systemd（缺 systemctl 或 /run/systemd/system）。二进制、配置、unit 已落盘，请在有 systemd 的机器上重跑本脚本"
    systemctl daemon-reload
    systemctl enable --now "${SERVICE_NAME}.service"
    # enable --now 理论上失败会非 0，这里再显式确认一次开机自启真的挂上了
    if ! systemctl is-enabled --quiet "${SERVICE_NAME}.service"; then
        die "systemctl enable 之后 is-enabled 仍不通过，开机自启未生效。手工排查: systemctl is-enabled ${SERVICE_NAME}"
    fi
    log "开机自启已启用（systemctl is-enabled ${SERVICE_NAME} 通过）"
    sleep 1
    if systemctl is-active --quiet "${SERVICE_NAME}.service"; then
        log "服务已启动，等待注册到 Server ..."
        verify_registered
    else
        systemctl --no-pager --full status "${SERVICE_NAME}.service" || true
        die "服务启动失败，日志: journalctl -u ${SERVICE_NAME} -n 100 --no-pager"
    fi
fi

cat <<EOF

[atagent] 安装完成
  server      : ${SERVER}
  tag         : ${TAG}
  agent-id    : ${AGENT_ID}
  concurrency : ${CONCURRENCY}
  二进制      : ${INSTALL_BIN}
  配置        : ${CONF_FILE}
  数据目录    : ${DATA_DIR}
  日志        : journalctl -u ${SERVICE_NAME} -f   （Agent 自身日志只写 journald）

常用命令:
  atagent status                   # 本机视角：connected 才是真在线
  systemctl status ${SERVICE_NAME}
  systemctl restart ${SERVICE_NAME}
  journalctl -u ${SERVICE_NAME} -n 200 --no-pager
EOF
