package com.atest.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.atest.common.ApiException;
import com.atest.common.DisplayTags;
import com.atest.config.AtestProperties;
import com.atest.web.dto.SshInstallRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 「SSH 代装」：Server 主动 SSH 到目标机，上传 atagent + install.sh + unit 模板，
 * 执行 {@code install.sh --server ... --tag ... --bin ./atagent}，把输出尾部带回页面。
 *
 * <p>安全约定（页面上也写了）：
 * <ul>
 *   <li>口令 / 私钥只在本次请求内存中使用，不落 H2、不打日志（本类日志只记 host/tag/结果码）；</li>
 *   <li>远端命令行里没有任何凭据；</li>
 *   <li>主机指纹默认 accept-new（首连记录、变更拒绝），可显式跳过（内网 v1）。</li>
 * </ul>
 */
@Slf4j
@Service
public class SshInstallService {

    /** 主机名或 IPv4；不含冒号（IPv6 v1 不支持，避免和 host:port 语法混淆） */
    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,255}$");
    private static final Pattern USER_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");
    private static final Pattern SERVER_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+:[0-9]{1,5}$");

    private final AtestProperties props;
    private final AgentDistService dist;
    private final TagConflictTracker tagConflictTracker;

    public SshInstallService(AtestProperties props, AgentDistService dist,
                             TagConflictTracker tagConflictTracker) {
        this.props = props;
        this.dist = dist;
        this.tagConflictTracker = tagConflictTracker;
    }

    public Map<String, Object> install(SshInstallRequest req) {
        validate(req);

        AgentDistService.BinaryInfo bin = dist.binaryInfo().orElseThrow(() ->
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "binary_missing", dist.missingBinaryHint()));
        byte[] binaryBytes;
        try {
            binaryBytes = Files.readAllBytes(bin.path());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "binary_missing",
                    "读取 atagent 二进制失败: " + rootMessage(e));
        }
        byte[] installSh = dist.classpathFile("install.sh");
        byte[] unitTpl = dist.classpathFile("atagent.service");

        long startedMs = System.currentTimeMillis();
        Instant startedAt = Instant.now();
        AtestProperties.SshInstall cfg = props.getSshInstall();
        AtomicBoolean hostKeyRejected = new AtomicBoolean();

        log.info("ssh-install start host={} port={} user={} tag={} auth={}",
                req.getHost(), req.getPort(), req.getUser(), req.getTag(), req.getAuthType());

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier(buildVerifier(req.isSkipHostKeyCheck(), hostKeyRejected));
            client.start();

            ClientSession session = null;
            try {
                try {
                    session = client.connect(req.getUser(), req.getHost(), req.getPort())
                            .verify(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                            .getSession();
                } catch (Exception e) {
                    return fail("connect_failed", "SSH 连接失败（" + req.getHost() + ":" + req.getPort()
                            + "）: " + rootMessage(e), null, "", startedMs);
                }

                Map<String, Object> authError = authenticate(session, req, cfg, hostKeyRejected, startedMs);
                if (authError != null) {
                    return authError;
                }

                String remoteDir = "/tmp/atagent-ssh-install."
                        + Long.toHexString(ThreadLocalRandom.current().nextLong() | (1L << 62));
                try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                    sftp.mkdir(remoteDir);
                    upload(sftp, remoteDir + "/atagent", binaryBytes, 0755);
                    upload(sftp, remoteDir + "/install.sh", installSh, 0755);
                    upload(sftp, remoteDir + "/atagent.service", unitTpl, 0644);
                } catch (Exception e) {
                    return fail("upload_failed", "上传安装文件失败: " + rootMessage(e), null, "", startedMs);
                }

                return execInstall(session, req, cfg, remoteDir, startedAt, startedMs);
            } finally {
                if (session != null) {
                    session.close(true);
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // 兜底：不往外抛堆栈，返回结构化结果（信息里不含任何凭据）
            log.warn("ssh-install unexpected failure host={} tag={}: {}", req.getHost(), req.getTag(), e.toString());
            return fail("internal_error", "SSH 安装内部错误: " + rootMessage(e), null, "", startedMs);
        }
    }

    /** 认证成功返回 null，失败返回结构化错误 */
    private Map<String, Object> authenticate(ClientSession session, SshInstallRequest req,
                                             AtestProperties.SshInstall cfg,
                                             AtomicBoolean hostKeyRejected, long startedMs) {
        if ("key".equals(req.getAuthType())) {
            Iterable<KeyPair> keys;
            try {
                keys = SecurityUtils.loadKeyPairIdentities(session,
                        NamedResource.ofName("pasted-private-key"),
                        new ByteArrayInputStream(req.getPrivateKey().getBytes(StandardCharsets.UTF_8)),
                        FilePasswordProvider.of(req.getPassphrase() == null ? "" : req.getPassphrase()));
            } catch (Exception e) {
                return fail("bad_private_key", "私钥解析失败（支持 OpenSSH/PEM 格式，加密私钥需附口令）: "
                        + rootMessage(e), null, "", startedMs);
            }
            boolean any = false;
            if (keys != null) {
                for (KeyPair kp : keys) {
                    session.addPublicKeyIdentity(kp);
                    any = true;
                }
            }
            if (!any) {
                return fail("bad_private_key", "私钥内容里没有可用的密钥", null, "", startedMs);
            }
        } else {
            session.addPasswordIdentity(req.getPassword());
        }
        try {
            session.auth().verify(Duration.ofMillis(cfg.getAuthTimeoutMs()));
            return null;
        } catch (Exception e) {
            if (hostKeyRejected.get()) {
                return fail("host_key_changed", "目标机主机指纹与 known_hosts 记录不一致，已拒绝连接。"
                        + "确认机器没被换过后，删除 Server 上 " + props.getSshInstall().getKnownHostsFile()
                        + " 里对应行，或勾选「跳过主机指纹校验」重试。", null, "", startedMs);
            }
            return fail("auth_failed", "SSH 认证失败（用户名/口令/私钥不对，或目标机禁止该认证方式）: "
                    + rootMessage(e), null, "", startedMs);
        }
    }

    private Map<String, Object> execInstall(ClientSession session, SshInstallRequest req,
                                            AtestProperties.SshInstall cfg, String remoteDir,
                                            Instant startedAt, long startedMs) throws Exception {
        String cmd = buildRemoteCommand(req, remoteDir);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ClientChannel channel = session.createExecChannel(cmd)) {
            channel.setOut(out);
            channel.setErr(out);
            channel.open().verify(Duration.ofMillis(cfg.getConnectTimeoutMs()));
            Set<ClientChannelEvent> events =
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), cfg.getExecTimeoutMs());
            String output = tail(out.toString(StandardCharsets.UTF_8), cfg.getOutputTailLines());
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                return fail("timeout", "安装超时（>" + cfg.getExecTimeoutMs() / 1000
                        + "s），已断开 SSH。目标机上可能残留 " + remoteDir + "，可手工清理。", null, output, startedMs);
            }
            Integer exit = channel.getExitStatus();
            if (exit != null && exit == 0) {
                log.info("ssh-install ok host={} tag={} in {}ms", req.getHost(), req.getTag(),
                        System.currentTimeMillis() - startedMs);
                Map<String, Object> res = base(true, exit, output, startedMs);
                res.put("message", "安装完成，机器 " + req.getTag() + " 已注册到 Server");
                return res;
            }
            if (tagConflictTracker.conflictSince(req.getTag(), startedAt) || output.contains("tag_conflict")) {
                return fail("tag_conflict", "机器名 " + req.getTag()
                        + " 已被另一台机器占用（tag_conflict），换一个名字重试。目标机上服务已安装、会持续重连，"
                        + "换名后重新安装即可。", exit, output, startedMs);
            }
            return fail("install_failed", "install.sh 退出码 " + exit + "，看下方输出排查。", exit, output, startedMs);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void validate(SshInstallRequest req) {
        String host = trimTo(req::getHost, req::setHost);
        if (host.isEmpty() || !HOST_PATTERN.matcher(host).matches()) {
            throw ApiException.badRequest("目标机地址只允许主机名或 IPv4（字母数字和 . _ -）");
        }
        if (req.getPort() < 1 || req.getPort() > 65535) {
            throw ApiException.badRequest("SSH 端口非法: " + req.getPort());
        }
        String user = trimTo(req::getUser, req::setUser);
        if (user.isEmpty() || !USER_PATTERN.matcher(user).matches()) {
            throw ApiException.badRequest("SSH 用户名非法: " + user);
        }
        req.setTag(DisplayTags.requireValidHttp(req.getTag()));
        String server = trimTo(req::getServer, req::setServer);
        if (!SERVER_PATTERN.matcher(server).matches()) {
            throw ApiException.badRequest("Server 地址格式应为 host:port（Agent TCP，通常是 :9800）");
        }
        int port = Integer.parseInt(server.substring(server.lastIndexOf(':') + 1));
        if (port < 1 || port > 65535) {
            throw ApiException.badRequest("Server 端口非法: " + port);
        }
        if (req.getConcurrency() < 1 || req.getConcurrency() > 4) {
            throw ApiException.badRequest("并发只能是 1-4");
        }
        if ("key".equals(req.getAuthType())) {
            if (req.getPrivateKey() == null || req.getPrivateKey().isBlank()) {
                throw ApiException.badRequest("认证方式为私钥时必须粘贴私钥内容");
            }
        } else if ("password".equals(req.getAuthType())) {
            if (req.getPassword() == null || req.getPassword().isEmpty()) {
                throw ApiException.badRequest("认证方式为口令时必须填写口令");
            }
        } else {
            throw ApiException.badRequest("authType 只能是 password 或 key");
        }
    }

    private static String trimTo(java.util.function.Supplier<String> get,
                                 java.util.function.Consumer<String> set) {
        String v = get.get() == null ? "" : get.get().trim();
        set.accept(v);
        return v;
    }

    private ServerKeyVerifier buildVerifier(boolean skip, AtomicBoolean rejected) {
        ServerKeyVerifier inner;
        if (skip) {
            inner = AcceptAllServerKeyVerifier.INSTANCE;
        } else {
            Path knownHosts = Path.of(props.getSshInstall().getKnownHostsFile()).toAbsolutePath().normalize();
            try {
                if (knownHosts.getParent() != null) {
                    Files.createDirectories(knownHosts.getParent());
                }
                if (!Files.exists(knownHosts)) {
                    Files.createFile(knownHosts);
                }
            } catch (Exception e) {
                log.warn("无法准备 known_hosts 文件 {}，本次退化为 accept-all: {}", knownHosts, e.toString());
                inner = AcceptAllServerKeyVerifier.INSTANCE;
                return recordRejections(inner, rejected);
            }
            // accept-new：没见过的指纹记下来，之后指纹变了拒绝
            inner = new KnownHostsServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE, knownHosts);
        }
        return recordRejections(inner, rejected);
    }

    private static ServerKeyVerifier recordRejections(ServerKeyVerifier delegate, AtomicBoolean rejected) {
        return (ClientSession session, SocketAddress remote, PublicKey key) -> {
            boolean ok = delegate.verifyServerKey(session, remote, key);
            if (!ok) {
                rejected.set(true);
            }
            return ok;
        };
    }

    private static void upload(SftpClient sftp, String path, byte[] content, int perms) throws Exception {
        try (OutputStream os = sftp.write(path, SftpClient.OpenMode.Write,
                SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
            os.write(content);
        }
        SftpClient.Attributes attrs = new SftpClient.Attributes();
        attrs.setPermissions(perms);
        sftp.setStat(path, attrs);
    }

    /**
     * 所有拼进命令行的值都过了白名单校验（无空格无引号），不存在注入面；
     * 命令行里没有任何凭据。失败时顺手带回 journalctl 尾部帮助定位（tag_conflict / 连不上 9800）。
     */
    private String buildRemoteCommand(SshInstallRequest req, String remoteDir) {
        String sudo = "root".equals(req.getUser()) ? "" : "sudo -n ";
        return "set -e; cd " + remoteDir + "; chmod 0755 install.sh atagent; set +e; "
                + sudo + "bash ./install.sh"
                + " --server " + req.getServer()
                + " --tag " + req.getTag()
                + " --concurrency " + req.getConcurrency()
                + " --bin ./atagent; rc=$?; "
                + "if [ $rc -ne 0 ]; then echo '--- journalctl -u atagent（尾部）---'; "
                + sudo + "journalctl -u atagent -n 25 --no-pager 2>/dev/null; fi; "
                + "cd /; rm -rf " + remoteDir + "; exit $rc";
    }

    private static Map<String, Object> base(boolean ok, Integer exitCode, String output, long startedMs) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", ok);
        res.put("exitCode", exitCode);
        res.put("output", output);
        res.put("durationMs", System.currentTimeMillis() - startedMs);
        return res;
    }

    private Map<String, Object> fail(String code, String message, Integer exitCode, String output, long startedMs) {
        log.info("ssh-install failed code={} ({}ms)", code, System.currentTimeMillis() - startedMs);
        Map<String, Object> res = base(false, exitCode, output, startedMs);
        res.put("errorCode", code);
        res.put("error", message);
        return res;
    }

    private static String tail(String text, int maxLines) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) {
            return text;
        }
        StringBuilder sb = new StringBuilder("...（输出较长，只保留最后 " + maxLines + " 行）\n");
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
