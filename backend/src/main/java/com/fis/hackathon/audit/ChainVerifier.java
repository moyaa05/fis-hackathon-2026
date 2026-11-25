package com.fis.hackathon.audit;

import com.fis.hackathon.shared.ChainReport;

import java.time.Instant;
import java.util.List;

/**
 * Recorre la cadena eslabón a eslabón y devuelve el PRIMER fallo (SPEC §4.4):
 *  SEQ_GAP       falta un número de secuencia (alguien borró un evento)
 *  BROKEN_LINK   prev_hash no apunta al eslabón anterior (alguien reordenó o insertó)
 *  HASH_MISMATCH el contenido no cuadra con su hash (alguien editó un evento)
 * Clase pura (sin Spring ni BD): se prueba con datos en memoria.
 */
public final class ChainVerifier {

    private ChainVerifier() {}

    public record Row(long seq, String eventType, String entityRef, String actor, Instant occurredAt,
                      String payload, String prevHash, String hash) {}

    public static ChainReport verify(List<Row> rowsOrderedBySeq, Instant checkedAt) {
        String prev = AuditHasher.GENESIS;
        long expectedSeq = 1;
        String head = rowsOrderedBySeq.isEmpty() ? AuditHasher.GENESIS : rowsOrderedBySeq.get(rowsOrderedBySeq.size() - 1).hash();
        long length = rowsOrderedBySeq.size();
        for (Row r : rowsOrderedBySeq) {
            if (r.seq() != expectedSeq) {
                return new ChainReport(false, length, head, checkedAt, r.seq(), "SEQ_GAP", null, null);
            }
            if (!r.prevHash().equals(prev)) {
                return new ChainReport(false, length, head, checkedAt, r.seq(), "BROKEN_LINK", prev, r.prevHash());
            }
            String recomputed = AuditHasher.hash(r.seq(), r.eventType(), r.entityRef(), r.actor(),
                    r.occurredAt(), r.payload(), r.prevHash());
            if (!recomputed.equals(r.hash())) {
                return new ChainReport(false, length, head, checkedAt, r.seq(), "HASH_MISMATCH", recomputed, r.hash());
            }
            prev = r.hash();
            expectedSeq++;
        }
        return ChainReport.ok(length, head, checkedAt);
    }
}
