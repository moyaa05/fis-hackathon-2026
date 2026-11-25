package com.fis.hackathon.payments.service;

import com.fis.hackathon.ledger.domain.PaymentEntity;
import com.fis.hackathon.ledger.repository.PaymentRepository;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@EnableScheduling
public class EscrowScheduler {

    private final PaymentRepository paymentRepo;
    private final PaymentService paymentService;

    public EscrowScheduler(PaymentRepository paymentRepo, PaymentService paymentService) {
        this.paymentRepo = paymentRepo;
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelay = 5000)
    public void checkExpiredEscrows() {
        Instant now = Instant.now();
        for (PaymentEntity p : paymentRepo.findByStatus("HELD")) {
            if (p.getEscrow() != null && p.getEscrow().get("expiresAt") != null) {
                Instant exp = Instant.parse(String.valueOf(p.getEscrow().get("expiresAt")));
                if (!exp.isAfter(now)) {
                    try {
                        paymentService.expirePayment(p.getId(), false);
                    } catch (Exception ignored) {
                        // ya resuelto concurrentemente
                    }
                }
            }
        }
    }
}
