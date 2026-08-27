package com.atest.repo;

import java.util.List;
import java.util.Optional;

import com.atest.domain.AgentEntity;
import com.atest.domain.AgentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<AgentEntity, String> {

    Optional<AgentEntity> findByDisplayTag(String displayTag);

    List<AgentEntity> findByStatus(AgentStatus status);

    List<AgentEntity> findAllByOrderByDisplayTagAsc();
}
