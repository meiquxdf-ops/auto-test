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

@Getter
@Setter
@Entity
@Table(name = "execution_log")
public class ExecutionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "execute_id", length = 64, nullable = false)
    private String executeId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "line", length = 65535, nullable = false)
    private String line;

    @Column(name = "bytes", nullable = false)
    private int bytes;

    @Column(name = "ts", nullable = false)
    private Instant ts;
}
