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

/** Agent reported event; (agentId, evtId) is the idempotency key. */
@Getter
@Setter
@Entity
@Table(name = "agent_event")
public class AgentEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId;

    @Column(name = "evt_id", length = 64, nullable = false)
    private String evtId;

    @Column(name = "type", length = 64, nullable = false)
    private String type;

    @Column(name = "execute_id", length = 64)
    private String executeId;

    @Column(name = "message", length = 65535)
    private String message;

    @Column(name = "event_time")
    private Instant eventTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
