package com.fis.hackathon.rules;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Cuenta los pagos ANTERIORES de este pagador a este proveedor, dentro de la ventana,
 * que el motor retuvo por ir a una wallet no verificada (SPEC §2).
 * Se apoya en el JSON de la decisión guardada: lo que el motor decidió EN SU MOMENTO.
 * Requisito para Antonio: guardar payment.decision con los checks tal cual (code + result).
 */
@Component
public class UnverifiedAttemptsQuery {

    private static final String SQL = """
            SELECT count(*) FROM payment
            WHERE payer_wallet = ? AND supplier_id = ? AND created_at > ?
              AND decision->'checks' @> '[{"code":"NEW_BENEFICIARY_WALLET","result":"HOLD"}]'::jsonb
            """;

    private final JdbcTemplate jdbc;

    public UnverifiedAttemptsQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int count(String payerWallet, String supplierId, Instant since) {
        Integer n = jdbc.queryForObject(SQL, Integer.class, payerWallet, supplierId, Timestamp.from(since));
        return n == null ? 0 : n;
    }
}
