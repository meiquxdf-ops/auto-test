package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * SSH 代装 kill switch：atest.ssh-install.enabled 默认 false，此时
 * POST /api/agent/ssh-install 一律 403（code=ssh_install_disabled，带开启指引），
 * 凭据连参数校验都不进入；其余安装面（install-info / 脚本 / 二进制分发）不受影响。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-ssh-disabled;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class SshInstallDisabledTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void sshInstallIsForbiddenByDefault() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // a fully valid payload still gets 403: the switch is checked before any validation
        ResponseEntity<Map> res = rest.postForEntity("/api/agent/ssh-install",
                new HttpEntity<>(Map.of("host", "10.0.0.9", "tag", "qa-node-01",
                        "server", "10.0.0.5:9800", "authType", "password", "password", "x"),
                        headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("code")).isEqualTo("ssh_install_disabled");
        assertThat((String) res.getBody().get("message")).contains("atest.ssh-install.enabled");
    }

    @Test
    void installInfoReportsTheSwitchSoThePageCanExplain() {
        ResponseEntity<Map> res = rest.getForEntity("/api/agent/install-info", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("sshInstallEnabled")).isEqualTo(false);
    }
}
