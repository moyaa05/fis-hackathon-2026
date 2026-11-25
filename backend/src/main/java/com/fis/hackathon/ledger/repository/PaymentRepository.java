package com.fis.hackathon.ledger.repository;

import com.fis.hackathon.ledger.domain.PaymentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);
    List<PaymentEntity> findAllByOrderByIdDesc(Pageable pageable);
    List<PaymentEntity> findByStatus(String status);
}
