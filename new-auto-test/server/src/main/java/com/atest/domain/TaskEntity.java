package com.atest.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "command", nullable = false, length = 65535)
    private String command;

    @Column(name = "cwd", length = 1024)
    private String cwd;

    /** JSON object */
    @Column(name = "env", length = 65535)
    private String env;

    /** JSON object, see JudgeService */
    @Column(name = "condition_config", length = 65535)
    private String conditionConfig;

    /** JSON array of the raw targets requested at creation time */
    @Column(name = "targets", length = 65535)
    private String targets;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "timeout_sec", nullable = false)
    private int timeoutSec;

    @Column(name = "priority", nullable = false)
    private int priority;

    /** manual queue position, lower runs first */
    @Column(name = "queue_order", nullable = false)
    private long queueOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "rerun_of")
    private Long rerunOf;

    /** caller supplied idempotency / grouping key; unique per create request, shared by a batch */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** POST here once when the task reaches finished / canceled */
    @Column(name = "callback_url", length = 1024)
    private String callbackUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "callback_status", length = 16, nullable = false)
    private CallbackStatus callbackStatus = CallbackStatus.NONE;

    @Column(name = "callback_attempts", nullable = false)
    private int callbackAttempts;

    @Column(name = "callback_last_error", length = 512)
    private String callbackLastError;

    @Column(name = "callback_last_at")
    private Instant callbackLastAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
