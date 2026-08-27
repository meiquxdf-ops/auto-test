package com.atest.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskExecutionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TaskExecutionRepository extends JpaRepository<TaskExecutionEntity, Long> {

    Optional<TaskExecutionEntity> findByExecuteId(String executeId);

    List<TaskExecutionEntity> findByTaskIdOrderByIdAsc(Long taskId);

    List<TaskExecutionEntity> findByTaskIdInOrderByIdAsc(Collection<Long> taskIds);

    List<TaskExecutionEntity> findByAgentIdAndStatusIn(String agentId, Collection<ExecutionStatus> statuses);

    long countByAgentIdAndStatusIn(String agentId, Collection<ExecutionStatus> statuses);

    @Query("select distinct e.agentId from TaskExecutionEntity e where e.status = com.atest.domain.ExecutionStatus.PENDING")
    List<String> findAgentIdsWithPending();

    /** Queue order: manual task order first, then priority, then creation order. */
    @Query("select e from TaskExecutionEntity e, TaskEntity t "
            + "where t.id = e.taskId and e.agentId = :agentId "
            + "and e.status = com.atest.domain.ExecutionStatus.PENDING "
            + "order by t.queueOrder asc, t.priority desc, e.id asc")
    List<TaskExecutionEntity> findPendingForAgent(@Param("agentId") String agentId, Pageable pageable);

    @Query("select e from TaskExecutionEntity e "
            + "where e.status in (com.atest.domain.ExecutionStatus.DISPATCHING, com.atest.domain.ExecutionStatus.RUNNING) "
            + "and e.leaseExpireAt is not null and e.leaseExpireAt < :now")
    List<TaskExecutionEntity> findExpiredLeases(@Param("now") Instant now);

    @Query("select e from TaskExecutionEntity e "
            + "where e.status = com.atest.domain.ExecutionStatus.RUNNING and e.startedAt is not null "
            + "and e.timeoutRequested = false and e.cancelRequested = false")
    List<TaskExecutionEntity> findRunningForTimeoutCheck();

    @Query("select e from TaskExecutionEntity e "
            + "where e.status in (com.atest.domain.ExecutionStatus.DISPATCHING, com.atest.domain.ExecutionStatus.RUNNING)")
    List<TaskExecutionEntity> findAllActive();

    @Query("select e.agentId, count(e) from TaskExecutionEntity e "
            + "where e.status in (com.atest.domain.ExecutionStatus.DISPATCHING, com.atest.domain.ExecutionStatus.RUNNING) "
            + "group by e.agentId")
    List<Object[]> countActivePerAgent();

    @Query("select e.status, count(e) from TaskExecutionEntity e group by e.status")
    List<Object[]> countByStatusGrouped();

    long countByTaskIdAndStatusIn(Long taskId, Collection<ExecutionStatus> statuses);

    /**
     * Lease acquisition. The row is only claimed when it is still pending, which makes the
     * scheduler safe against duplicate dispatch without any in-memory lock.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TaskExecutionEntity e set e.status = com.atest.domain.ExecutionStatus.DISPATCHING, "
            + "e.dispatchToken = :token, e.leaseExpireAt = :leaseExpireAt, e.dispatchedAt = :now, "
            + "e.acked = false, e.subStatus = null, e.updatedAt = :now "
            + "where e.id = :id and e.status = com.atest.domain.ExecutionStatus.PENDING")
    int casClaim(@Param("id") Long id,
                 @Param("token") String token,
                 @Param("leaseExpireAt") Instant leaseExpireAt,
                 @Param("now") Instant now);

    /** Give the slot back when the agent refuses or the frame never made it out. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TaskExecutionEntity e set e.status = com.atest.domain.ExecutionStatus.PENDING, "
            + "e.dispatchToken = null, e.leaseExpireAt = null, e.dispatchedAt = null, e.acked = false, "
            + "e.subStatus = null, e.updatedAt = :now "
            + "where e.id = :id and e.status = com.atest.domain.ExecutionStatus.DISPATCHING and e.dispatchToken = :token")
    int casRelease(@Param("id") Long id, @Param("token") String token, @Param("now") Instant now);

    /**
     * Flags a cancel request on a still-live row only. The caller's entity snapshot may be stale
     * (a racing fin can finalize the row between load and here), so the flag must be a targeted
     * conditional UPDATE: an entity save would write the whole stale row and resurrect a terminal
     * execution back to running.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TaskExecutionEntity e set e.cancelRequested = true, e.updatedAt = :now "
            + "where e.id = :id and e.status in (com.atest.domain.ExecutionStatus.PENDING, "
            + "com.atest.domain.ExecutionStatus.DISPATCHING, com.atest.domain.ExecutionStatus.RUNNING)")
    int casMarkCancelRequested(@Param("id") Long id, @Param("now") Instant now);

    /** Same contract as {@link #casMarkCancelRequested} for the watchdog timeout flag. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TaskExecutionEntity e set e.timeoutRequested = true, e.updatedAt = :now "
            + "where e.id = :id and e.status in (com.atest.domain.ExecutionStatus.PENDING, "
            + "com.atest.domain.ExecutionStatus.DISPATCHING, com.atest.domain.ExecutionStatus.RUNNING)")
    int casMarkTimeoutRequested(@Param("id") Long id, @Param("now") Instant now);
}
