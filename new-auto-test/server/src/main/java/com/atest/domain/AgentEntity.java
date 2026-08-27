package com.atest.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agent")
public class AgentEntity {

    @Id
    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId;

    @Column(name = "display_tag", length = 128, nullable = false)
    private String displayTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private AgentStatus status = AgentStatus.OFFLINE;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "boot_id", length = 64)
    private String bootId;

    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "remote_addr", length = 128)
    private String remoteAddr;

    /** comma separated alternative names reported in hello */
    @Column(name = "aliases", length = 2048)
    private String aliases;

    @Column(name = "concurrency", nullable = false)
    private int concurrency = 1;

    @Column(name = "running_count", nullable = false)
    private int runningCount;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isOnline() {
        return status == AgentStatus.ONLINE;
    }
}
