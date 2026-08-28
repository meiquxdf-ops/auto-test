package com.atest.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import com.atest.common.ApiException;
import com.atest.config.AtestProperties;
import com.atest.domain.TaskAttachmentEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.TaskAttachmentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.web.dto.AttachmentView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件存 Server 本地磁盘（atest.attachments.dir），元数据进 task_attachment 表。
 *
 * 线程模型：请求线程上只做校验（任务/执行存在、单文件 32MB 上限），落盘 + 建档整体丢进
 * 专用有界线程池（uploadExecutor），控制器返回 CompletableFuture 释放 Tomcat 线程。
 * 池满 -> 429；磁盘写失败 -> 503；全程流式 transferTo，绝不 getBytes() 整读进堆。
 */
@Slf4j
@Service
public class AttachmentService {

    private final AtestProperties props;
    private final TaskAttachmentRepository attachmentRepository;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final ExecutorService uploadExecutor;

    public AttachmentService(AtestProperties props,
                             TaskAttachmentRepository attachmentRepository,
                             TaskRepository taskRepository,
                             TaskExecutionRepository executionRepository,
                             @Qualifier("uploadExecutor") ExecutorService uploadExecutor) {
        this.props = props;
        this.attachmentRepository = attachmentRepository;
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
        this.uploadExecutor = uploadExecutor;
    }

    /** 运维台 / 开放调用：直接挂到任务。 */
    public CompletableFuture<AttachmentView> uploadForTask(Long taskId, MultipartFile file) {
        requireTask(taskId);
        return store(taskId, null, file);
    }

    /** Agent 上的脚本：用下发注入的 ATEST_EXECUTE_ID 挂到本次执行（附带归属任务）。 */
    public CompletableFuture<AttachmentView> uploadForExecution(String executeId, MultipartFile file) {
        TaskExecutionEntity exec = executionRepository.findByExecuteId(executeId)
                .orElseThrow(() -> ApiException.notFound("execution 不存在: " + executeId));
        return store(exec.getTaskId(), exec.getExecuteId(), file);
    }

    public List<AttachmentView> listForTask(Long taskId) {
        requireTask(taskId);
        return attachmentRepository.findByTaskIdOrderByIdAsc(taskId).stream().map(this::toView).toList();
    }

    public TaskAttachmentEntity require(Long fileId) {
        return attachmentRepository.findById(fileId)
                .orElseThrow(() -> ApiException.notFound("附件不存在: " + fileId));
    }

    public Path diskPathOf(TaskAttachmentEntity entity) {
        return baseDir().resolve(entity.getStoredName());
    }

    public AttachmentView toView(TaskAttachmentEntity e) {
        return new AttachmentView(e.getId(), e.getTaskId(), e.getExecuteId(), e.getFileName(),
                e.getSizeBytes(), e.getContentType(), e.getCreatedAt());
    }

    // ------------------------------------------------------------------ store

    private CompletableFuture<AttachmentView> store(Long taskId, String executeId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("file 不能为空");
        }
        long max = props.getAttachments().getMaxBytes();
        if (file.getSize() > max) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                    "文件 " + file.getSize() + " 字节超过单附件上限 " + max + " 字节");
        }
        String originalName = originalNameOf(file);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "-" + sanitize(originalName);
        String contentType = normalizeContentType(file.getContentType());
        try {
            return CompletableFuture.supplyAsync(
                    () -> writeAndRecord(taskId, executeId, file, originalName, storedName, contentType),
                    uploadExecutor);
        } catch (RejectedExecutionException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_uploads",
                    "附件上传并发已满，请稍后重试");
        }
    }

    /** Runs on the bounded upload pool, never on a Tomcat worker. */
    private AttachmentView writeAndRecord(Long taskId, String executeId, MultipartFile file,
                                          String originalName, String storedName, String contentType) {
        Path dir = baseDir();
        Path dest = dir.resolve(storedName);
        try {
            Files.createDirectories(dir);
            // 目标必须是绝对路径：Servlet Part.write 对相对路径会写进容器临时目录
            file.transferTo(dest);
        } catch (IOException e) {
            log.error("attachment write failed for task {} ({}): {}", taskId, storedName, e.toString());
            deleteQuietly(dest);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable",
                    "附件写盘失败: " + e.getMessage());
        }
        try {
            TaskAttachmentEntity entity = new TaskAttachmentEntity();
            entity.setTaskId(taskId);
            entity.setExecuteId(executeId);
            entity.setFileName(originalName);
            entity.setStoredName(storedName);
            entity.setContentType(contentType);
            entity.setSizeBytes(file.getSize());
            entity.setCreatedAt(Instant.now());
            attachmentRepository.save(entity);
            return toView(entity);
        } catch (RuntimeException e) {
            deleteQuietly(dest);
            throw e;
        }
    }

    private void requireTask(Long taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw ApiException.notFound("task 不存在: " + taskId);
        }
    }

    private Path baseDir() {
        return Path.of(props.getAttachments().getDir()).toAbsolutePath().normalize();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    // ------------------------------------------------------------ file names

    private static String originalNameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "file";
        }
        // 浏览器/客户端可能带整段路径，只留末段
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            name = "file";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /**
     * 磁盘名消毒：只允许 {@code [A-Za-z0-9._-]}，其余（含中文、空格、控制符）替换成下划线；
     * 去掉打头的点（防 dotfile / ..），最长 128。原始名原样存 DB，仅展示与下载头使用。
     */
    static String sanitize(String original) {
        String name = original == null ? "" : original.replaceAll("[^A-Za-z0-9._-]", "_");
        name = name.replaceFirst("^\\.+", "");
        if (name.isBlank()) {
            name = "file";
        }
        return name.length() > 128 ? name.substring(name.length() - 128) : name;
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String v = contentType.trim();
        return v.length() > 128 ? v.substring(0, 128) : v;
    }
}
