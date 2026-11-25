package com.fis.hackathon.audit;

import com.fis.hackathon.shared.ChainReport;

import java.util.Map;

/**
 * ENCHUFE entre Antonio y Alejandro: el registro a prueba de manipulación.
 * REGLA DE ORO: llamar a append() DENTRO de la misma transacción que cambia el dinero/estado,
 * para que nunca haya un cambio sin su evento ni un evento sin su cambio.
 * Tipos de evento y campos del payload: SPEC §4.5.
 */
public interface AuditLog {
    /** Devuelve el hash del evento añadido. */
    String append(String eventType, String entityRef, String actor, Map<String, Object> payload);

    ChainReport verify();
}
