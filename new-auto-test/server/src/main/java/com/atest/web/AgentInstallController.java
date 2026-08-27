package com.atest.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.atest.common.ApiException;
import com.atest.config.AtestProperties;
import com.atest.service.AgentDistService;
import com.atest.service.SshInstallService;
import com.atest.web.dto.SshInstallRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Agent 在线安装（内网）：
 *
 * <pre>
 * GET  /api/agent/install-info            页面用：二进制是否可分发、sha256、TCP 端口等
 * GET  /api/agent/install.sh              curl | sudo bash 的引导脚本（参数透传给真 install.sh）
 * GET  /api/agent/files/install.sh        真 deploy/install.sh（打包进 jar 的那份）
 * GET  /api/agent/files/atagent.service   systemd unit 模板
 * GET  /api/agent/binary                  linux/amd64 静态 atagent 二进制
 * POST /api/agent/ssh-install             「SSH 代装」：Server SSH 到目标机执行安装
 * </pre>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentInstallController {

    private static final MediaType SHELL = MediaType.parseMediaType("text/x-shellscript;charset=UTF-8");

    private final AtestProperties props;
    private final AgentDistService dist;
    private final SshInstallService sshInstallService;

    public AgentInstallController(AtestProperties props, AgentDistService dist, SshInstallService sshInstallService) {
        this.props = props;
        this.dist = dist;
        this.sshInstallService = sshInstallService;
    }

    @GetMapping("/install-info")
    public Map<String, Object> installInfo() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("agentTcpPort", props.getAgent().getPort());
        res.put("distDir", props.getAgentDist().getDir());
        AgentDistService.BinaryInfo bin = dist.binaryInfo().orElse(null);
        res.put("binaryAvailable", bin != null);
        if (bin != null) {
            res.put("binaryPath", bin.path().toString());
            res.put("binarySize", bin.sizeBytes());
            res.put("binarySha256", bin.sha256());
            res.put("binaryModifiedAt", Instant.ofEpochMilli(bin.modifiedAt().toEpochMilli()).toString());
            res.put("binaryElf", bin.elf());
        } else {
            res.put("hint", dist.missingBinaryHint());
        }
        return res;
    }

    /** curl -fsSL http://server:8080/api/agent/install.sh | sudo bash -s -- --tag xxx --server host:9800 */
    @GetMapping(value = "/install.sh")
    public ResponseEntity<String> bootstrap() {
        AgentDistService.BinaryInfo bin = dist.binaryInfo().orElseThrow(() ->
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "binary_missing", dist.missingBinaryHint()));
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ResponseEntity.ok().contentType(SHELL).body(dist.bootstrapScript(base, bin));
    }

    @GetMapping("/files/install.sh")
    public ResponseEntity<String> realInstallScript() {
        return ResponseEntity.ok().contentType(SHELL)
                .body(new String(dist.classpathFile("install.sh"), StandardCharsets.UTF_8));
    }

    @GetMapping("/files/atagent.service")
    public ResponseEntity<String> unitTemplate() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(new String(dist.classpathFile("atagent.service"), StandardCharsets.UTF_8));
    }

    @GetMapping("/binary")
    public ResponseEntity<Resource> binary() {
        AgentDistService.BinaryInfo bin = dist.binaryInfo().orElseThrow(() ->
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "binary_missing", dist.missingBinaryHint()));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"atagent\"")
                .header("X-Checksum-Sha256", bin.sha256())
                .contentLength(bin.sizeBytes())
                .body(new FileSystemResource(bin.path()));
    }

    @PostMapping("/ssh-install")
    public Map<String, Object> sshInstall(@RequestBody SshInstallRequest request) {
        return sshInstallService.install(request);
    }
}
