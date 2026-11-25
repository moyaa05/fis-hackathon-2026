package com.fis.hackathon.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis.hackathon.shared.ChainReport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La cadena de audit tiene que dar EXACTAMENTE los hashes de test-vectors.json y detectar cualquier manipulación. */
class AuditVectorsTest {

    private JsonNode vectors() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/test-vectors.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private List<ChainVerifier.Row> rows() throws Exception {
        List<ChainVerifier.Row> rows = new ArrayList<>();
        for (JsonNode e : vectors().get("audit").get("events")) {
            rows.add(new ChainVerifier.Row(e.get("seq").asLong(), e.get("eventType").asText(), e.get("entityRef").asText(),
                    e.get("actor").asText(), Instant.parse(e.get("occurredAt").asText()), e.get("payloadCanonical").asText(),
                    e.get("prevHash").asText(), e.get("hash").asText()));
        }
        return rows;
    }

    @Test
    void reproducesEveryVectorHash() throws Exception {
        for (ChainVerifier.Row r : rows()) {
            String h = AuditHasher.hash(r.seq(), r.eventType(), r.entityRef(), r.actor(), r.occurredAt(), r.payload(), r.prevHash());
            assertEquals(r.hash(), h, "seq " + r.seq());
        }
    }

    @Test
    void canonicalJsonMatchesVector() throws Exception {
        JsonNode cj = vectors().get("canonicalJson");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = new ObjectMapper().convertValue(cj.get("input"), Map.class);
        assertEquals(cj.get("expected").asText(), CanonicalJson.write(input));
    }

    @Test
    void validChainVerifies() throws Exception {
        assertTrue(ChainVerifier.verify(rows(), Instant.now()).valid());
    }

    @Test
    void detectsEditedPayload() throws Exception {
        List<ChainVerifier.Row> rows = rows();
        ChainVerifier.Row r = rows.get(1);
        rows.set(1, new ChainVerifier.Row(r.seq(), r.eventType(), r.entityRef(), r.actor(), r.occurredAt(),
                r.payload().replace("15000.00", "1500.00"), r.prevHash(), r.hash()));
        ChainReport report = ChainVerifier.verify(rows, Instant.now());
        assertFalse(report.valid());
        assertEquals("HASH_MISMATCH", report.reason());
        assertEquals(2L, report.firstInvalidSeq());
    }

    @Test
    void detectsDeletedEvent() throws Exception {
        List<ChainVerifier.Row> rows = rows();
        rows.remove(1);
        ChainReport report = ChainVerifier.verify(rows, Instant.now());
        assertEquals("SEQ_GAP", report.reason());
    }

    @Test
    void timestampAlwaysHasThreeDecimals() {
        assertEquals("2026-11-25T21:04:05.000Z", AuditHasher.MILLIS.format(Instant.parse("2026-11-25T21:04:05Z")));
    }
}
