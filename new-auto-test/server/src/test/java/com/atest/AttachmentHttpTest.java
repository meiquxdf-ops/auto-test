package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.domain.TaskStatus;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 附件 HTTP 面：脚本按执行回传 / 运维台按任务上传、列表、下载（Content-Disposition）、
 * 未知任务与执行 404、超限拒绝（413）。
 * 超限用例不提交 32MB 夹具：把 atest.attachments.max-bytes 压到 64 字节，用 65 字节的
 * 文件走同一条应用层拒绝路径（容器层 spring.servlet.multipart 32MB 的生产默认值由
 * {@link AttachmentDefaultsTest} 单独断言）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-attach;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0",
        "atest.attachments.max-bytes=64"
})
class AttachmentHttpTest {

    @TempDir
    static Path attachDir;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    TaskExecutionRepository executionRepository;

    @DynamicPropertySource
    static void attachProps(DynamicPropertyRegistry registry) {
        registry.add("atest.attachments.dir", () -> attachDir.toString());
    }

    TaskEntity newTask() {
        Instant now = Instant.now();
        TaskEntity task = new TaskEntity();
        task.setCommand("echo attach-test");
        task.setStatus(TaskStatus.PENDING);
        task.setRequestId(UUID.randomUUID().toString());
        task.setTimeoutSec(600);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return taskRepository.save(task);
    }

    TaskExecutionEntity newExecution(TaskEntity task) {
        Instant now = Instant.now();
        TaskExecutionEntity exec = new TaskExecutionEntity();
        exec.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
        exec.setTaskId(task.getId());
        exec.setAgentId("agent-attach-test");
        exec.setStatus(ExecutionStatus.RUNNING);
        exec.setCreatedAt(now);
        exec.setUpdatedAt(now);
        return executionRepository.save(exec);
    }

    static HttpEntity<MultiValueMap<String, Object>> multipart(String filename, byte[] content) {
        ByteArrayResource file = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    @Test
    @SuppressWarnings("unchecked")
    void scriptUploadThenListThenDownload() {
        TaskEntity task = newTask();
        TaskExecutionEntity exec = newExecution(task);
        byte[] content = "hello-attachment".getBytes();

        // 脚本视角：POST $ATEST_HTTP_BASE/api/executions/$ATEST_EXECUTE_ID/files
        ResponseEntity<Map> up = rest.postForEntity("/api/executions/{eid}/files",
                multipart("result.txt", content), Map.class, exec.getExecuteId());
        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(up.getBody().get("name")).isEqualTo("result.txt");
        assertThat(((Number) up.getBody().get("size")).longValue()).isEqualTo(content.length);
        assertThat(up.getBody().get("executeId")).isEqualTo(exec.getExecuteId());
        Number fileId = (Number) up.getBody().get("id");

        // 挂在了归属任务下
        ResponseEntity<List> list = rest.getForEntity("/api/tasks/{id}/files", List.class, task.getId());
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);
        Map<String, Object> item = (Map<String, Object>) list.getBody().get(0);
        assertThat(item.get("name")).isEqualTo("result.txt");

        // 任务视图带 attachmentCount，前端列表不用逐个再查
        ResponseEntity<Map> taskView = rest.getForEntity("/api/tasks/{id}", Map.class, task.getId());
        assertThat(((Number) taskView.getBody().get("attachmentCount")).longValue()).isEqualTo(1L);

        // 下载：内容一致 + attachment 头
        ResponseEntity<byte[]> down = rest.getForEntity("/api/files/{id}", byte[].class, fileId);
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(down.getBody()).isEqualTo(content);
        String disposition = down.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).contains("attachment").contains("result.txt");
    }

    @Test
    void opsUploadToTaskAndFilenameIsSanitizedOnDisk() throws Exception {
        TaskEntity task = newTask();
        ResponseEntity<Map> up = rest.postForEntity("/api/tasks/{id}/files",
                multipart("../../报告 final.txt", "x".getBytes()), Map.class, task.getId());
        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 原始名（去掉路径段）进 DB 供展示
        assertThat(up.getBody().get("name")).isEqualTo("报告 final.txt");
        assertThat(up.getBody().get("executeId")).isNull();

        // 磁盘名 = {uuid}-{消毒后}，绝不含路径穿越与非常规字符
        Number fileId = (Number) up.getBody().get("id");
        try (var stream = Files.list(attachDir)) {
            List<Path> stored = stream.filter(p -> {
                try {
                    return new String(Files.readAllBytes(p)).equals("x");
                } catch (Exception e) {
                    return false;
                }
            }).toList();
            assertThat(stored).hasSize(1);
            // 中文与空格全部替换为下划线：___final.txt
            assertThat(stored.get(0).getFileName().toString()).matches("^[0-9a-f]{32}-_+final\\.txt$");
        }
        assertThat(fileId).isNotNull();
    }

    @Test
    void oversizeIsRejectedBeforeStorage() {
        TaskEntity task = newTask();
        // max-bytes=64（测试覆盖值），65 字节必须 413
        byte[] tooBig = new byte[65];
        ResponseEntity<Map> up = rest.postForEntity("/api/tasks/{id}/files",
                multipart("big.bin", tooBig), Map.class, task.getId());
        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(up.getBody().get("code")).isEqualTo("file_too_large");

        // 拒绝发生在落盘前：任务名下没有任何附件
        ResponseEntity<List> list = rest.getForEntity("/api/tasks/{id}/files", List.class, task.getId());
        assertThat(list.getBody()).isEmpty();
    }

    @Test
    void unknownTaskOrExecutionIs404() {
        ResponseEntity<Map> upTask = rest.postForEntity("/api/tasks/999999/files",
                multipart("a.txt", "a".getBytes()), Map.class);
        assertThat(upTask.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> upExec = rest.postForEntity("/api/executions/no-such-exec/files",
                multipart("a.txt", "a".getBytes()), Map.class);
        assertThat(upExec.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> list = rest.getForEntity("/api/tasks/999999/files", Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> down = rest.getForEntity("/api/files/999999", Map.class);
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingFilePartIsBadRequest() {
        TaskEntity task = newTask();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("other", "not-a-file");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> up = rest.postForEntity("/api/tasks/{id}/files",
                new HttpEntity<>(body, headers), Map.class, task.getId());
        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
