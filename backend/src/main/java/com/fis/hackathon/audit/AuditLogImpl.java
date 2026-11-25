package com.fis.hackathon.audit;

import com.fis.hackathon.ledger.LedgerLock;
import com.fis.hackathon.shared.ChainReport;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Implementación REAL del registro encadenado (sustituye a StubAuditLog).
 *
 * append(): coge el turno (candado), lee el último eslabón, calcula el nuevo hash y lo guarda,
 * todo dentro de la transacción de quien llama -> o se guarda el cambio Y su evento, o ninguno.
 */
@Component
public class AuditLogImpl implements AuditLog {

    private final AuditEventRepository repository;
    private final EntityManager entityManager;
    private final LedgerLock ledgerLock;

    public AuditLogImpl(AuditEventRepository repository, EntityManager entityManager, LedgerLock ledgerLock) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.ledgerLock = ledgerLock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)   // debe ir dentro de la transacción del llamador
    public String append(String eventType, String entityRef, String actor, Map<String, Object> payload) {
        ledgerLock.acquire();                               // reentrante: si el llamador ya lo tiene, no pasa nada
        var last = repository.findTopByOrderBySeqDesc();
        long seq = last.map(e -> e.getSeq() + 1).orElse(1L);
        String prevHash = last.map(AuditEventEntity::getHash).orElse(AuditHasher.GENESIS);
        Instant at = AuditHasher.now();
        String canonical = CanonicalJson.write(payload == null ? Map.of() : payload);
        String hash = AuditHasher.hash(seq, eventType, entityRef, actor, at, canonical, prevHash);
        entityManager.persist(new AuditEventEntity(seq, eventType, entityRef, actor, canonical, at, prevHash, hash));
        entityManager.flush();                              // que el siguiente append de esta misma tx lo vea
        return hash;
    }

    @Override
    @Transactional(readOnly = true)
    public ChainReport verify() {
        var rows = repository.findAllByOrderBySeqAsc().stream().map(AuditEventEntity::toRow).toList();
        return ChainVerifier.verify(rows, Instant.now());
    }
}
