package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 在线安装 HTTP 面：/api/agent/install-info、install.sh 引导脚本、真 install.sh、
 * unit 模板、二进制分发与 sha256，以及 /api/agent/ssh-install 的参数校验。
 * （真正的 SSH 连接需要目标机，不在单测范围。）
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-install;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0",
        // SSH 代装默认关闭（kill switch），这里显式打开以测参数校验路径
        "atest.ssh-install.enabled=true"
})
class AgentInstallHttpTest {

    @TempDir
    static Path distDir;

    static byte[] fakeBinary;

    @Autowired
    TestRestTemplate rest;

    @DynamicPropertySource
    static void distProps(DynamicPropertyRegistry registry) {
        registry.add("atest.agent-dist.dir", () -> distDir.toString());
    }

    @BeforeAll
    static void writeFakeBinary() throws Exception {
        // ELF 魔数开头的假二进制，够测 sha256 与 ELF 识别
        fakeBinary = new byte[]{0x7f, 'E', 'L', 'F', 1, 2, 3, 4, 5, 6};
        Files.write(distDir.resolve("atagent"), fakeBinary);
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void installInfoReportsBinary() throws Exception {
        ResponseEntity<Map> res = rest.getForEntity("/api/agent/install-info", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("binaryAvailable")).isEqualTo(true);
        assertThat(res.getBody().get("binarySha256")).isEqualTo(sha256(fakeBinary));
        assertThat(res.getBody().get("binaryElf")).isEqualTo(true);
        assertThat(res.getBody().get("agentTcpPort")).isNotNull();
    }

    @Test
    void bootstrapScriptEmbedsBaseUrlAndSha256() throws Exception {
        ResponseEntity<String> res = rest.getForEntity("/api/agent/install.sh", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = res.getBody();
        assertThat(body).startsWith("#!/usr/bin/env bash");
        assertThat(body).contains("BIN_SHA256=\"" + sha256(fakeBinary) + "\"");
        assertThat(body).contains("/api/agent/files/install.sh");
        assertThat(body).contains("/api/agent/files/atagent.service");
        // 参数透传给真 install.sh，一行命令的 --tag/--server 就是这么进去的
        assertThat(body).contains("--url \"$BASE_URL/api/agent/binary\" --sha256 \"$BIN_SHA256\" \"$@\"");
    }

    @Test
    void realInstallScriptIsTheDeployOne() {
        ResponseEntity<String> res = rest.getForEntity("/api/agent/files/install.sh", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String script = res.getBody();
        // deploy/install.sh 的特征内容：unit 渲染与注册验证
        assertThat(script).contains("SERVICE_NAME=\"atagent\"");
        assertThat(script).contains("verify_registered");
        // 写 unit 前必须先建 /etc/systemd/system：没有该目录的机器（容器/WSL/无 systemd）
        // 曾在 render_unit 的重定向处直接被 set -e 打断（"No such file or directory"）
        assertThat(script).contains("install -d -m 0755 \"$(dirname \"$UNIT_FILE\")\"");
        // 开机自启是安装的一部分：默认路径必须 enable --now 并确认 is-enabled，
        // 唯一的合法跳过方式是显式 --no-enable（做基础镜像用）
        assertThat(script).contains("systemctl enable --now \"${SERVICE_NAME}.service\"");
        assertThat(script).contains("systemctl is-enabled");
        assertThat(script).contains("--no-enable");
        // 曾经的静默降级路径（没有 systemd 时跳过 unit / enable 却照样报安装成功）不允许回归
        assertThat(script).doesNotContain("跳过 unit 写入");
        assertThat(script).doesNotContain("跳过 enable/start；手动启动");
        // 残留进程收割不允许只按进程名（pgrep -x atagent）匹配：那会误杀跑在其他路径的
        // 同名进程（如 compose 开发环境的 /tmp/atagent）。必须再按 /proc/<pid>/exe
        // 确认真实可执行文件是本机安装的 INSTALL_BIN，被覆盖的旧二进制（" (deleted)"）也算
        assertThat(script).contains("readlink -f \"/proc/${pid}/exe\"");
        assertThat(script).contains("exe=\"${exe% (deleted)}\"");
        assertThat(script).contains("[[ \"$exe\" == \"$INSTALL_BIN\" ]] || continue");
    }

    @Test
    void unitTemplateIsServed() {
        ResponseEntity<String> res = rest.getForEntity("/api/agent/files/atagent.service", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("@BIN@");
    }

    @Test
    void binaryIsServedWithChecksumHeader() throws Exception {
        ResponseEntity<byte[]> res = rest.getForEntity("/api/agent/binary", byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo(fakeBinary);
        assertThat(res.getHeaders().getFirst("X-Checksum-Sha256")).isEqualTo(sha256(fakeBinary));
    }

    @Test
    void sshInstallValidatesBeforeConnecting() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // tag 缺失
        ResponseEntity<Map> res = rest.postForEntity("/api/agent/ssh-install",
                new HttpEntity<>(Map.of("host", "10.0.0.9", "server", "10.0.0.5:9800",
                        "authType", "password", "password", "x"), headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // server 端口写成 HTTP 语法之外的东西
        res = rest.postForEntity("/api/agent/ssh-install",
                new HttpEntity<>(Map.of("host", "10.0.0.9", "tag", "qa-node-01",
                        "server", "http://10.0.0.5:8080",
                        "authType", "password", "password", "x"), headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 私钥认证但没贴私钥
        res = rest.postForEntity("/api/agent/ssh-install",
                new HttpEntity<>(Map.of("host", "10.0.0.9", "tag", "qa-node-01",
                        "server", "10.0.0.5:9800", "authType", "key"), headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // host 里带注入面直接拒绝
        res = rest.postForEntity("/api/agent/ssh-install",
                new HttpEntity<>(Map.of("host", "10.0.0.9; rm -rf /", "tag", "qa-node-01",
                        "server", "10.0.0.5:9800", "authType", "password", "password", "x"), headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
