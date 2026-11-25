package com.fis.hackathon.audit;

import com.fis.hackathon.shared.ChainReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * PROVISIONAL (stub). Se BORRA cuando llegue AuditLogImpl (entrega 02).
 * No guarda nada: solo escribe el evento en la consola para que Antonio vea que lo llama bien.
 */
@Component
public class StubAuditLog implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger(StubAuditLog.class);
    private static final String ZEROS = "0".repeat(64);

    @Override
    public String append(String eventType, String entityRef, String actor, Map<String, Object> payload) {
        log.info("[AUDIT-STUB] {} {} by {} -> {}", eventType, entityRef, actor, payload);
        return ZEROS;
    }

    @Override
    public ChainReport verify() {
        return ChainReport.ok(0, ZEROS, Instant.now());
    }
}
