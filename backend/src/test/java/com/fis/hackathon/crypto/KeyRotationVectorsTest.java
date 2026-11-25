package com.fis.hackathon.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis.hackathon.shared.RotationResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comprueba la clasificación de firmas contra test-vectors.json (generado y verificado también con OpenSSL). */
class KeyRotationVectorsTest {

    private final KeyRotationServiceImpl service = new KeyRotationServiceImpl();

    private JsonNode vectors() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/test-vectors.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void classifiesEveryVectorCase() throws Exception {
        JsonNode ed = vectors().get("ed25519");
        String challenge = ed.get("challenge").asText();
        String trusted = ed.get("trustedPublicKey").asText();
        String requested = ed.get("requestedPublicKey").asText();
        for (JsonNode c : ed.get("cases")) {
            RotationResult got = service.verify(challenge, c.get("signature").asText(), trusted, requested);
            assertEquals(RotationResult.valueOf(c.get("expected").asText()), got, c.get("name").asText());
        }
    }

    @Test
    void neverThrowsOnGarbage() {
        assertEquals(RotationResult.MALFORMED, service.verify("x", "%%%not-base64%%%", "AAAA", "AAAA"));
        assertEquals(RotationResult.MALFORMED, service.verify("x", null, null, null));
        String sixtyFourZeroBytes = java.util.Base64.getEncoder().encodeToString(new byte[64]);
        assertEquals(RotationResult.INVALID, service.verify("x", sixtyFourZeroBytes, "not-a-key", "not-a-key"));
    }

    @Test
    void challengeHasTheFrozenFormat() throws Exception {
        String expected = vectors().get("ed25519").get("challenge").asText();
        String nonce = expected.lines().filter(l -> l.startsWith("nonce=")).findFirst().orElseThrow().substring(6);
        String built = service.buildChallenge(3, "ACME", "tdgb:acme:op-01", "tdgb:acmetrading:7731",
                new BigDecimal("80000"), nonce, Instant.parse("2026-11-25T23:02:41Z"));
        assertEquals(expected, built);
        assertTrue(service.newNonce().matches("[A-Za-z0-9_-]{22}"));
    }
}
