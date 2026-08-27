package com.atest.repo;

import java.time.Instant;

import com.atest.domain.OpenRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpenRequestRepository extends JpaRepository<OpenRequestEntity, String> {

    /**
     * Claims a requestId. A plain {@code save()} would merge (select-then-update) on an assigned
     * id and silently swallow a duplicate, so the claim is a raw INSERT: when two callers race on
     * the same key the primary key makes the loser fail with a constraint violation, which the
     * service turns into the 409 of the duplicate contract.
     */
    @Modifying
    @Query(value = "insert into open_request (request_id, source, created_at) values (:id, :source, :now)",
            nativeQuery = true)
    void claim(@Param("id") String requestId, @Param("source") String source, @Param("now") Instant now);
}
