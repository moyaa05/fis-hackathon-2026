package com.fis.hackathon.rules;

import java.time.Instant;
import java.util.Map;

/** Una regla tal y como la usa el motor (copia inmutable de la fila de la tabla rule). */
public record RuleDef(
        String code,
        String name,
        String description,
        String outcome,      // HOLD | BLOCK
        boolean enabled,
        int priority,
        Map<String, Object> params,
        int version,
        String updatedBy,
        Instant updatedAt
) {}
