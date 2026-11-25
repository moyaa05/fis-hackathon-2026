package com.fis.hackathon.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis.hackathon.shared.ChainReport;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lectura del audit para la UI.
 * Antonio: en GET /api/state usa summary(12) para el bloque "audit" (SPEC §5.1 AppState.audit).
 */
@Service
public class AuditReadService {

    public record AuditEventView(long seq, String eventType, String entityRef, String actor, String occurredAt,
                                 Map<String, Object> payload, String prevHash, String hash) {}

    public record AuditSummary(long length, String headHash, boolean chainValid, Long firstInvalidSeq,
                               List<AuditEventView> tail) {}

    public record AuditList(long length, String headHash, List<AuditEventView> events) {}

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final AuditEventRepository repository;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;

    public AuditReadService(AuditEventRepository repository, AuditLog auditLog, ObjectMapper mapper) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.mapper = mapper;
    }

    /** Resumen para la cabecera: ¿cadena válida? + últimos N eventos (más reciente primero). */
    @Transactional(readOnly = true)
    public AuditSummary summary(int tailSize) {
        ChainReport report = auditLog.verify();
        return new AuditSummary(report.length(), report.headHash(), report.valid(), report.firstInvalidSeq(), latest(tailSize));
    }

    /** GET /api/audit?limit=N */
    @Transactional(readOnly = true)
    public AuditList list(int limit) {
        var head = repository.findTopByOrderBySeqDesc();
        return new AuditList(repository.count(), head.map(AuditEventEntity::getHash).orElse(AuditHasher.GENESIS), latest(limit));
    }

    private List<AuditEventView> latest(int limit) {
        return repository.findAllByOrderBySeqDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                .stream().map(this::toView).toList();
    }

    private AuditEventView toView(AuditEventEntity e) {
        Map<String, Object> payload;
        try {
            payload = mapper.readValue(e.getPayload(), MAP);
        } catch (Exception tamperedOrBroken) {
            payload = Map.of("_raw", e.getPayload());   // si alguien rompió el JSON a mano, se muestra tal cual
        }
        return new AuditEventView(e.getSeq(), e.getEventType(), e.getEntityRef(), e.getActor(),
                AuditHasher.MILLIS.format(e.getOccurredAt()), payload, e.getPrevHash(), e.getHash());
    }
}
