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
@Table(name = "task_execution")
public class TaskExecutionEntity {

    /** disconnected is a sub state of running, never a terminal state */
    public static final String SUB_DISCONNECTED = "disconnected";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** wire identity handed to the agent; in-place rerun mints a fresh one per attempt */
    @Column(name = "execute_id", length = 64, nullable = false)
    private String executeId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** resolved and frozen at ingest time, never re-resolved */
    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId;

    @Column(name = "agent_tag", length = 128)
    private String agentTag;

    /** what the caller actually typed (tag or agentId) */
    @Column(name = "target_raw", length = 128)
    private String targetRaw;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @Column(name = "sub_status", length = 24)
    private String subStatus;

    @Column(name = "dispatch_token", length = 64)
    private String dispatchToken;

    @Column(name = "lease_expire_at")
    private Instant leaseExpireAt;

    @Column(name = "acked", nullable = false)
    private boolean acked;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "timeout_requested", nullable = false)
    private boolean timeoutRequested;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "last_line", length = 65535)
    private String lastLine;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "matched_rule", length = 512)
    private String matchedRule;

    @Column(name = "log_seq", nullable = false)
    private int logSeq;

    @Column(name = "log_min_seq", nullable = false)
    private int logMinSeq;

    @Column(name = "log_bytes", nullable = false)
    private long logBytes;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
