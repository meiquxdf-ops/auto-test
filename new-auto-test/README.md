# new-auto-test

分布式测试执行平台（全新实现）。代码全部在本目录，与仓库内旧的 `auto-test-*` 模块无关。

- `docs/` 协议与规格
- `server/` Java Spring Boot（HTTP :8080 + Agent TCP :9800）
- `agent/` Go 单二进制
- `frontend/` Vue 3 运维台
- `deploy/` 安装脚本与 systemd

详见 `docs/protocol.md`。
