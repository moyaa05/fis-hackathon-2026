package com.fis.hackathon.crypto;

import com.fis.hackathon.shared.RotationResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * PROVISIONAL (stub). Se BORRA cuando llegue KeyRotationServiceImpl (entrega 02).
 * newNonce y buildChallenge ya son los DEFINITIVOS (formato exacto SPEC §3.1);
 * solo verify() es de mentira: acepta "LEGIT" como válida y "ATTACKER" como autoautorización.
 */
@Component
public class StubKeyRotationService implements KeyRotationService {

    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private final SecureRandom random = new SecureRandom();

    @Override
    public String newNonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String buildChallenge(long paymentId, String supplierId, String trustedWallet, String requestedWallet,
                                 BigDecimal amount, String nonce, Instant expiresAt) {
        return String.join("\n",
                "FIS-KEYROT-v1",
                "payment=" + paymentId,
                "supplier=" + supplierId,
                "trustedWallet=" + trustedWallet,
                "requestedWallet=" + requestedWallet,
                "amount=" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                "currency=GBPT",
                "nonce=" + nonce,
                "expiresAt=" + SECONDS.format(expiresAt));
    }

    @Override
    public RotationResult verify(String challenge, String signatureB64, String trustedPublicKeyB64, String requestedPublicKeyB64) {
        if ("LEGIT".equals(signatureB64)) return RotationResult.VALID;
        if ("ATTACKER".equals(signatureB64)) return RotationResult.SELF_AUTHORISATION;
        return RotationResult.INVALID;
    }
}
