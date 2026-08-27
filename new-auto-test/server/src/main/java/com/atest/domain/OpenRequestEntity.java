package com.atest.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The consumed-requestId registry. A batch shares one requestId across several task rows, so the
 * task table cannot carry a unique index on request_id; global uniqueness (duplicate -> 409) is
 * instead enforced by this table's primary key. Rows are only ever inserted (claimed) inside the
 * creating transaction, so a failed create never consumes its key.
 */
@Getter
@Setter
@Entity
@Table(name = "open_request")
public class OpenRequestEntity {

    @Id
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** who minted the key: single | batch | auto | rerun | backfill */
    @Column(name = "source", length = 16, nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
