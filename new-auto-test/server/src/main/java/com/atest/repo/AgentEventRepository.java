package com.atest.repo;

import java.util.List;

import com.atest.domain.AgentEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentEventRepository extends JpaRepository<AgentEventEntity, Long> {

    boolean existsByAgentIdAndEvtId(String agentId, String evtId);

    List<AgentEventEntity> findByAgentIdOrderByIdDesc(String agentId, Pageable pageable);

    List<AgentEventEntity> findByExecuteIdOrderByIdDesc(String executeId, Pageable pageable);

    List<AgentEventEntity> findByAgentIdAndExecuteIdOrderByIdDesc(String agentId, String executeId, Pageable pageable);

    List<AgentEventEntity> findAllByOrderByIdDesc(Pageable pageable);

    @Query("select coalesce(max(e.evtId), '') from AgentEventEntity e where e.agentId = :agentId")
    String maxEvtId(@Param("agentId") String agentId);
}
