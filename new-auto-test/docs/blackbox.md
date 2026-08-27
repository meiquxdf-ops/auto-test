# 黑盒验收（浏览器 · 测试下发页）

入口：`http://127.0.0.1:5173/#/playground`  
默认 Agent tag：`docker-agent-01`（compose 内）

前置：`cd new-auto-test/deploy && make build-server && docker compose up -d`  
确认 `http://127.0.0.1:8080/actuator/health` 为 UP，前端 5173 可打开。

UI 对照（不要用英文枚举当唯一断言）：

| 协议状态 | 页面徽章文案 |
|---|---|
| pass | 通过 |
| fail | 失败 |
| block | 阻塞 |
| canceled | 已取消 |
| online | 在线 |

日志截断横幅原文：`日志已截断：单次执行仅保留末 5MB`

## TC01 机器上线

前置：compose 中 `atest-agent` 已连上。
步骤：打开测试下发页，看「目标机器」选择器，必要时点刷新。
期望：出现 `docker-agent-01`，状态「在线」。

## TC02 echo 成功 → pass

步骤：选 docker-agent-01，命令 `echo hello-atest`，判定配置保持关闭，点「立即下发」。
期望：右侧日志出现 `hello-atest`，徽章「通过」。

## TC03 无配置 exit 非 0 → fail

步骤：点快捷「故意失败」，或命令 `echo boom; exit 3`。判定保持关闭。立即下发。
期望：徽章「失败」，exitCode 3。

## TC04 include → block

步骤：展开「判定配置（可选）」，打开「启用判定配置」。
算子选「包含（include）」，匹配值 `MATCH_BLOCK`，判定为「阻塞」；other 留空。
命令 `echo MATCH_BLOCK`（exit 0，最后一行命中规则）。可用「判定预演」先贴 `MATCH_BLOCK` 看会判阻塞。
期望：徽章「阻塞」，退出码仍为 0——判定优先于退出码。

## TC05 取消 → canceled

步骤：关掉判定配置。命令 `sleep 60; echo 0`，下发后等右侧出现「执行中」，立刻点「取消本次下发」，弹窗点「确认取消」。
期望：徽章「已取消」；机器重新「在线」，不被 sleep 占住。

## TC06 cwd 与 env

步骤：cwd 填 `/tmp`。展开「环境变量」，添加 `FOO=bar`。命令：

```
pwd; echo FOO=$FOO; env | grep '^ATEST_' | sort; echo 0
```

立即下发。
期望：日志含 `/tmp`、`FOO=bar`，以及 `ATEST_AGENT_ID` / `ATEST_EXECUTE_ID` / `ATEST_TAG`；徽章「通过」。

## TC07 空命令 / 未选机器

步骤：清空命令或清空目标机器，观察「立即下发」。
期望：按钮 disabled，文案「请先填写命令并选择至少一台机器」，不产生新任务。

## TC08 日志截断横幅

步骤：命令：

```
python3 -c "import sys; sys.stdout.write('A'*6000000); print(); print(0)"
```

若容器无 python3，改用：

```
dd if=/dev/zero bs=1M count=6 2>/dev/null | tr '\0' 'A'; echo; echo 0
```

或 awk 按行刷（alpine 一般自带）：

```
awk 'BEGIN{for(i=1;i<=70000;i++) printf "line %06d %s\n", i, "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"; print 0}'
```

期望：终端顶部出现「日志已截断：单次执行仅保留末 5MB」。截断不影响判定，exit 0 仍为「通过」。行号第一行应明显大于 1。

## TC09 离线目标仍可创建（排队）

步骤：已选 docker-agent-01 时把 Agent 停掉，等选择器变为「离线」。期望出现「已选机器中有 1 台不在线」黄条，此时「立即下发」仍可点，任务进入「排队中」。再拉起 Agent，应自动调度到终态。

未选中的离线机器不能新勾选。空命令/空目标仍是前端拦截，不会打到服务端 400。

## 本环境 Docker 已知问题

Cloud Agent 默认 overlayfs snapshotter 解压部分镜像会失败：

```
failed to convert whiteout file "tmp/hsperfdata_root/.wh.69": operation not permitted
```

规避：compose 已改为 `eclipse-temurin:17-jre-jammy` + 预编译 jar + alpine 前端/Agent。
仍失败时：`dockerd` 用 vfs（关掉 containerd-snapshotter）。容器能起但 `npm`/`apk` 无网时，把 **iptables-legacy FORWARD** 改成 ACCEPT（见 `docs/runbook.md` §11）。
`command: >` 续行缩进多了会把 `-data-dir` 拆成下一条命令，表现为 `tag_conflict`；已在 compose 里对齐缩进。
