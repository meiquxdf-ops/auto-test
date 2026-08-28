package com.atest.repo;

import java.time.Instant;
import java.util.List;

import com.atest.domain.CallbackStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    Page<TaskEntity> findByStatus(TaskStatus status, Pageable pageable);

    List<TaskEntity> findByStatusOrderByQueueOrderAscIdAsc(TaskStatus status);

    @Query("select coalesce(max(t.queueOrder), 0) from TaskEntity t")
    long maxQueueOrder();

    boolean existsByRequestId(String requestId);

    List<TaskEntity> findByRequestIdOrderByIdAsc(String requestId, Pageable pageable);

    /**
     * The one-shot claim of a callback delivery: only the caller that flips pending -> running
     * may send, so a task never produces two callbacks no matter how many triggers race.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TaskEntity t set t.callbackStatus = com.atest.domain.CallbackStatus.RUNNING, t.updatedAt = :now "
            + "where t.id = :id and t.callbackUrl is not null "
            + "and t.callbackStatus = com.atest.domain.CallbackStatus.PENDING")
    int casClaimCallback(@Param("id") Long id, @Param("now") Instant now);

    /** Progress of a failed attempt while more retries remain. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TaskEntity t set t.callbackAttempts = :attempts, t.callbackLastError = :error, "
            + "t.callbackLastAt = :now, t.updatedAt = :now "
            + "where t.id = :id and t.callbackStatus = com.atest.domain.CallbackStatus.RUNNING")
    int recordCallbackAttempt(@Param("id") Long id,
                              @Param("attempts") int attempts,
                              @Param("error") String error,
                              @Param("now") Instant now);

    /** Terminal outcome (success / failed); guarded on running so a rerun reset is never clobbered. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TaskEntity t set t.callbackStatus = :status, t.callbackAttempts = :attempts, "
            + "t.callbackLastError = :error, t.callbackLastAt = :now, t.updatedAt = :now "
            + "where t.id = :id and t.callbackStatus = com.atest.domain.CallbackStatus.RUNNING")
    int finishCallback(@Param("id") Long id,
                       @Param("status") CallbackStatus status,
                       @Param("attempts") int attempts,
                       @Param("error") String error,
                       @Param("now") Instant now);

    /** Terminal tasks whose callback never got claimed (e.g. server restarted before sending). */
    @Query("select t.id from TaskEntity t where t.callbackUrl is not null "
            + "and t.callbackStatus = com.atest.domain.CallbackStatus.PENDING "
            + "and t.status in (com.atest.domain.TaskStatus.FINISHED, com.atest.domain.TaskStatus.CANCELED) "
            + "order by t.id asc")
    List<Long> findCallbackBacklog(Pageable pageable);

    /**
     * Boot recovery: a callback claimed (RUNNING) by a previous server process can never finish,
     * because the retry chain lived only in that process's memory and the backlog sweep selects
     * PENDING rows only. Requeue it so the sweep re-fires the delivery. Guarded on
     * {@code updatedAt < bootTime} so a callback legitimately claimed after this boot is never
     * reset (that would double-send it).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TaskEntity t set t.callbackStatus = com.atest.domain.CallbackStatus.PENDING, t.updatedAt = :now "
            + "where t.callbackUrl is not null "
            + "and t.callbackStatus = com.atest.domain.CallbackStatus.RUNNING "
            + "and t.updatedAt < :bootTime")
    int requeueStuckCallbacks(@Param("bootTime") Instant bootTime, @Param("now") Instant now);
}
