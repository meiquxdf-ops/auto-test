package com.atest.repo;

import java.util.List;

import com.atest.domain.TaskEntity;
import com.atest.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    Page<TaskEntity> findByStatus(TaskStatus status, Pageable pageable);

    List<TaskEntity> findByStatusOrderByQueueOrderAscIdAsc(TaskStatus status);

    @Query("select coalesce(max(t.queueOrder), 0) from TaskEntity t")
    long maxQueueOrder();
}
