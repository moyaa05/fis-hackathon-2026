package com.fis.hackathon.shared;

import java.time.Instant;

/**
 * Resultado de verificar la cadena de audit (SPEC §4.4).
 * reason: null | HASH_MISMATCH | BROKEN_LINK | SEQ_GAP
 * expectedHash = hash recalculado con el contenido ACTUAL de la fila; storedHash = el que hay guardado.
 */
public record ChainReport(
        boolean valid,
        long length,
        String headHash,
        Instant checkedAt,
        Long firstInvalidSeq,
        String reason,
        String expectedHash,
        String storedHash
) {
    public static ChainReport ok(long length, String headHash, Instant checkedAt) {
        return new ChainReport(true, length, headHash, checkedAt, null, null, null, null);
    }
}
