package com.atest.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 附件元数据；文件内容在 Server 本地磁盘（atest.attachments.dir）里的 {@code storedName}。 */
@Getter
@Setter
@Entity
@Table(name = "task_attachment")
public class TaskAttachmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 脚本上传时带的执行标识；运维台直接传给任务时为空 */
    @Column(name = "execute_id", length = 64)
    private String executeId;

    /** 调用方给的原始文件名（仅展示 / 下载头用，绝不参与磁盘路径） */
    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    /** 磁盘上的实际文件名：{uuid}-{safeName}，全局唯一且已消毒 */
    @Column(name = "stored_name", length = 320, nullable = false)
    private String storedName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
