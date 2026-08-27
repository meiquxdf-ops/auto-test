package com.atest.repo;

import java.util.List;

import com.atest.domain.DispatchEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchEventRepository extends JpaRepository<DispatchEventEntity, Long> {

    List<DispatchEventEntity> findByAgentIdOrderByIdDesc(String agentId, Pageable pageable);

    List<DispatchEventEntity> findByExecuteIdOrderByIdDesc(String executeId, Pageable pageable);

    List<DispatchEventEntity> findByAgentIdAndExecuteIdOrderByIdDesc(String agentId, String executeId, Pageable pageable);

    List<DispatchEventEntity> findAllByOrderByIdDesc(Pageable pageable);
}
