package com.atest.repo;

import java.util.Collection;
import java.util.List;

import com.atest.domain.TaskAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachmentEntity, Long> {

    List<TaskAttachmentEntity> findByTaskIdOrderByIdAsc(Long taskId);

    long countByTaskId(Long taskId);

    /** 任务列表页一把捞出各任务的附件数，避免逐行 count 的 N+1 */
    @Query("select a.taskId, count(a) from TaskAttachmentEntity a where a.taskId in :taskIds group by a.taskId")
    List<Object[]> countByTaskIdGrouped(@Param("taskIds") Collection<Long> taskIds);
}
