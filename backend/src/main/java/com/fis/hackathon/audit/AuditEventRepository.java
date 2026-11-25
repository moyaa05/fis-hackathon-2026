package com.fis.hackathon.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    Optional<AuditEventEntity> findTopByOrderBySeqDesc();

    List<AuditEventEntity> findAllByOrderBySeqAsc();

    List<AuditEventEntity> findAllByOrderBySeqDesc(Pageable pageable);
}
