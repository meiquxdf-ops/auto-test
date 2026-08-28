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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 手册第 1 节的状态码约定：调用方把请求写坏了是 4xx，不是 5xx。
 * 路径/查询参数类型不符、JSON 体读不出来、方法/Content-Type 不对，以前都会掉进
 * GlobalExceptionHandler 的兜底分支变成 500 internal_error —— 接入方的「5xx 重试」
 * 会把同一个坏请求反复打回来，Server 侧还每次打一条 ERROR 堆栈。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-badreq;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class MalformedRequestStatusTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void nonNumericPathVariableIsBadRequest() {
        for (String path : new String[]{"/api/tasks/abc", "/api/tasks/abc/files", "/api/files/abc"}) {
            ResponseEntity<Map> rsp = rest.getForEntity(path, Map.class);
            assertThat(rsp.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rsp.getBody().get("code")).as(path).isEqualTo("bad_request");
            assertThat(rsp.getBody().get("path")).as(path).isEqualTo(path);
        }
    }

    @Test
    void nonNumericQueryParamIsBadRequest() {
        ResponseEntity<Map> rsp = rest.getForEntity("/api/tasks?page=abc", Map.class);
        assertThat(rsp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rsp.getBody().get("code")).isEqualTo("bad_request");
    }

    @Test
    void malformedJsonBodyIsBadRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> rsp = rest.postForEntity("/api/tasks",
                new HttpEntity<>("{\"command\": ", headers), Map.class);
        assertThat(rsp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rsp.getBody().get("code")).isEqualTo("bad_request");
    }

    @Test
    void missingBodyIsBadRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> rsp = rest.exchange("/api/tasks", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);
        assertThat(rsp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rsp.getBody().get("code")).isEqualTo("bad_request");
    }

    @Test
    void unsupportedMethodIs405WithAllow() {
        ResponseEntity<Map> rsp = rest.exchange("/api/tasks/1", HttpMethod.DELETE,
                HttpEntity.EMPTY, Map.class);
        assertThat(rsp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(rsp.getBody().get("code")).isEqualTo("method_not_allowed");
        assertThat(rsp.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("GET");
    }

    @Test
    void unsupportedContentTypeIs415() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Map> rsp = rest.postForEntity("/api/tasks",
                new HttpEntity<>("hi", headers), Map.class);
        assertThat(rsp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(rsp.getBody().get("code")).isEqualTo("unsupported_media_type");
    }

    @Test
    void nonMultipartUploadStaysBadRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> rsp = rest.postForEntity("/api/tasks/1/files",
                new HttpEntity<>("{}", headers), Map.class);
        assertThat(rsp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rsp.getBody().get("code")).isEqualTo("bad_request");
    }
}
