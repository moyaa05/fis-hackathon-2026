package com.fis.hackathon.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Turno único" para todo lo que mueve dinero o escribe en el audit (SPEC §4.3).
 * Es un candado de PostgreSQL que se suelta solo al hacer commit: así dos operaciones
 * nunca leen el mismo "último hash" ni el mismo saldo a la vez, aunque haya varias réplicas.
 *
 * Uso: primera línea de todo método @Transactional que modifique datos -> ledgerLock.acquire();
 * MANDATORY = si alguien lo llama fuera de una transacción, falla en el acto (mejor que un bug silencioso).
 */
@Component
public class LedgerLock {

    private static final long LOCK_ID = 4242L;
    private final JdbcTemplate jdbc;

    public LedgerLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + LOCK_ID + ")");
    }
}
