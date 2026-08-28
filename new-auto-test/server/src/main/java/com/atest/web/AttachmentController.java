package com.atest.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.atest.common.ApiException;
import com.atest.domain.TaskAttachmentEntity;
import com.atest.service.AttachmentService;
import com.atest.web.dto.AttachmentView;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件 HTTP 面。上传接口返回 CompletableFuture：Tomcat 线程在校验后立即释放，
 * 落盘在专用有界线程池里完成（池满 429、写盘失败 503，见 AttachmentService）。
 */
@RestController
@RequestMapping("/api")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /** 脚本回传：用下发注入的 ATEST_EXECUTE_ID（$ATEST_HTTP_BASE/api/executions/$ATEST_EXECUTE_ID/files）。 */
    @PostMapping("/executions/{executeId}/files")
    public CompletableFuture<AttachmentView> uploadForExecution(@PathVariable String executeId,
                                                                @RequestPart("file") MultipartFile file) {
        return attachmentService.uploadForExecution(executeId, file);
    }

    /** 运维台 / 开放调用：直接给任务补附件。 */
    @PostMapping("/tasks/{taskId}/files")
    public CompletableFuture<AttachmentView> uploadForTask(@PathVariable Long taskId,
                                                           @RequestPart("file") MultipartFile file) {
        return attachmentService.uploadForTask(taskId, file);
    }

    @GetMapping("/tasks/{taskId}/files")
    public List<AttachmentView> list(@PathVariable Long taskId) {
        return attachmentService.listForTask(taskId);
    }

    /**
     * 下载。默认 Content-Disposition: attachment；UI 里预览图片/文本用 ?inline=1
     * 走同一个地址，不另开 blob 接口。
     */
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable Long fileId,
                                             @RequestParam(defaultValue = "false") boolean inline) {
        TaskAttachmentEntity entity = attachmentService.require(fileId);
        Path path = attachmentService.diskPathOf(entity);
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("附件文件已不在磁盘上: " + entity.getFileName());
        }
        ContentDisposition disposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(entity.getFileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(mediaTypeOf(entity.getContentType()))
                .contentLength(entity.getSizeBytes())
                .body(new FileSystemResource(path));
    }

    private static MediaType mediaTypeOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
