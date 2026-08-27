package com.atest.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.atest.config.AtestProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * atagent 安装物料的唯一出口。
 *
 * <p>install.sh 与 atagent.service 在打包时从 deploy/ 被拷进 jar（classpath:agent-dist/），
 * 与仓库永远一致；linux/amd64 静态二进制是平台产物、不进 git 也不进 jar，
 * 运行时按顺序从 {@code atest.agent-dist.dir}、../dist/agent、../agent 下找 atagent
 * （后两个是源码仓库开发布局的兜底）。填充方式见 deploy/README.md「在线安装」。
 */
@Slf4j
@Service
public class AgentDistService {

    public record BinaryInfo(Path path, long sizeBytes, Instant modifiedAt, String sha256, boolean elf) {
    }

    private final AtestProperties props;

    /** sha256 只在文件（路径、大小、mtime）变化时重算 */
    private volatile BinaryInfo cached;

    public AgentDistService(AtestProperties props) {
        this.props = props;
    }

    /** 找不到二进制时给页面/接口的中文指引 */
    public String missingBinaryHint() {
        return "Server 上没有可分发的 atagent 二进制（找遍 " + candidates()
                + "）。在能编译 Go 的机器上执行 cd deploy && make agent-dist（等价于 cd agent && "
                + "make static，产物拷到 <仓库根>/dist/agent/atagent），或把 linux/amd64 静态编译的 atagent "
                + "放到 Server 的 " + props.getAgentDist().getDir() + "/ 下。docker compose 编排里 agent "
                + "容器构建完会自动把产物拷进该目录。";
    }

    private List<Path> candidates() {
        return List.of(
                Path.of(props.getAgentDist().getDir(), "atagent"),
                Path.of("../dist/agent/atagent"),
                Path.of("../agent/atagent"));
    }

    public Optional<Path> resolveBinary() {
        for (Path p : candidates()) {
            if (Files.isRegularFile(p)) {
                return Optional.of(p.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    public Optional<BinaryInfo> binaryInfo() {
        Path path = resolveBinary().orElse(null);
        if (path == null) {
            return Optional.empty();
        }
        try {
            long size = Files.size(path);
            Instant mtime = Files.getLastModifiedTime(path).toInstant();
            BinaryInfo hit = cached;
            if (hit != null && hit.path().equals(path) && hit.sizeBytes() == size
                    && hit.modifiedAt().equals(mtime)) {
                return Optional.of(hit);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] head = new byte[4];
            int headLen = 0;
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (headLen < 4) {
                        int take = Math.min(4 - headLen, n);
                        System.arraycopy(buf, 0, head, headLen, take);
                        headLen += take;
                    }
                    digest.update(buf, 0, n);
                }
            }
            boolean elf = headLen == 4 && head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
            BinaryInfo info = new BinaryInfo(path, size, mtime, HexFormat.of().formatHex(digest.digest()), elf);
            cached = info;
            return Optional.of(info);
        } catch (Exception e) {
            log.warn("读取 atagent 二进制失败: {}", path, e);
            return Optional.empty();
        }
    }

    /** classpath:agent-dist/ 下的打包物料（install.sh / atagent.service） */
    public byte[] classpathFile(String name) {
        ClassPathResource res = new ClassPathResource("agent-dist/" + name);
        try (InputStream in = res.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("jar 里缺少 agent-dist/" + name
                    + "，打包时应从 deploy/ 拷入（见 server/pom.xml resources 配置）", e);
        }
    }

    /**
     * `curl -fsSL http://server:8080/api/agent/install.sh | sudo bash -s -- ...` 拿到的引导脚本：
     * 从同一台 Server 下载真正的 deploy/install.sh 与 unit 模板，再以 --url/--sha256 执行，
     * 命令行参数原样透传给 install.sh。
     */
    public String bootstrapScript(String baseUrl, BinaryInfo bin) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return """
                #!/usr/bin/env bash
                #
                # atagent 在线安装引导脚本（由 atest-server 动态生成，仅限内网）
                #
                #   curl -fsSL %1$s/api/agent/install.sh | sudo bash -s -- --tag qa-node-01 --server <host>:9800
                #
                # 参数原样透传给 deploy/install.sh（--tag / --server / --concurrency ...），
                # 二进制从本 Server 下载并做 sha256 校验。
                set -euo pipefail

                BASE_URL="%1$s"
                BIN_SHA256="%2$s"

                command -v curl >/dev/null 2>&1 || { echo "[atagent] 错误: 目标机上没有 curl" >&2; exit 1; }

                TMP_DIR="$(mktemp -d /tmp/atagent-bootstrap.XXXXXX)"
                trap 'rm -rf "$TMP_DIR"' EXIT

                echo "[atagent] 从 ${BASE_URL} 下载 install.sh 与 unit 模板 ..."
                curl -fsSL --connect-timeout 10 --retry 3 -o "$TMP_DIR/install.sh" "$BASE_URL/api/agent/files/install.sh"
                curl -fsSL --connect-timeout 10 --retry 3 -o "$TMP_DIR/atagent.service" "$BASE_URL/api/agent/files/atagent.service"
                chmod 0755 "$TMP_DIR/install.sh"

                bash "$TMP_DIR/install.sh" --url "$BASE_URL/api/agent/binary" --sha256 "$BIN_SHA256" "$@"
                """.formatted(base, bin.sha256());
    }
}
