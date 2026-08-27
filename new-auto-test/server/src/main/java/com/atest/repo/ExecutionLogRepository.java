package com.atest.repo;

import java.util.List;

import com.atest.domain.ExecutionLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLogEntity, Long> {

    @Query("select l from ExecutionLogEntity l where l.executeId = :executeId and l.seq >= :fromSeq order by l.seq asc")
    List<ExecutionLogEntity> findPage(@Param("executeId") String executeId,
                                      @Param("fromSeq") int fromSeq,
                                      Pageable pageable);

    /** Oldest first, used by the 5MB tail trimmer. */
    @Query("select l from ExecutionLogEntity l where l.executeId = :executeId order by l.seq asc")
    List<ExecutionLogEntity> findOldest(@Param("executeId") String executeId, Pageable pageable);

    @Transactional
    @Modifying
    @Query("delete from ExecutionLogEntity l where l.executeId = :executeId")
    int deleteByExecuteId(@Param("executeId") String executeId);

    @Query("select coalesce(min(l.seq), 0) from ExecutionLogEntity l where l.executeId = :executeId")
    int minSeq(@Param("executeId") String executeId);
}
