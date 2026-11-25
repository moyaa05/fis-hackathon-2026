package com.fis.hackathon.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * La fórmula del eslabón (SPEC §4.1):
 *   hash = SHA-256( seq|eventType|entityRef|actor|occurredAt|payload|prevHash )
 * Cada eslabón incluye el hash del anterior: si alguien toca un evento, su hash deja de cuadrar.
 */
public final class AuditHasher {

    private AuditHasher() {}

    public static final String GENESIS = "0".repeat(64);

    /** Formato FIJO con 3 decimales. NO usar Instant.toString(): se come el ".000". */
    public static final DateTimeFormatter MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Para campos de payload tipo escrowExpiresAt (SPEC §4.5). */
    public static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** "Ahora" ya recortado a milisegundos: PostgreSQL guarda microsegundos y redondea, así nunca hay desfase. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public static String hash(long seq, String eventType, String entityRef, String actor,
                              Instant occurredAt, String payloadCanonical, String prevHash) {
        String input = seq + "|" + eventType + "|" + entityRef + "|" + actor + "|"
                + MILLIS.format(occurredAt) + "|" + payloadCanonical + "|" + prevHash;
        return sha256Hex(input);
    }

    public static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
