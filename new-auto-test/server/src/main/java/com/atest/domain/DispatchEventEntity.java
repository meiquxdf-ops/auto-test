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

/** Server side timeline entry (dispatch decisions, state transitions, operator actions). */
@Getter
@Setter
@Entity
@Table(name = "dispatch_event")
public class DispatchEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "execute_id", length = 64)
    private String executeId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "type", length = 64, nullable = false)
    private String type;

    @Column(name = "detail", length = 65535)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
