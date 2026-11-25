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
 * Implementación REAL de KeyRotationService (sustituye a StubKeyRotationService).
 *
 * En cristiano: cuando un pago va a una wallet nueva, el banco genera un texto ("challenge") que dice
 * exactamente qué se está autorizando (pago, proveedor, wallet vieja, wallet nueva, importe, nonce, caducidad).
 * El proveedor de verdad lo firma con la clave de su wallet de siempre. Aquí comprobamos QUIÉN lo firmó.
 */
@Component
public class KeyRotationServiceImpl implements KeyRotationService {

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
                "FIS-KEYROT-v1",                       // dominio: esta firma no vale para nada más
                "payment=" + paymentId,                // no se puede reutilizar en otro pago
                "supplier=" + supplierId,
                "trustedWallet=" + trustedWallet,
                "requestedWallet=" + requestedWallet,  // no se puede cambiar el destino
                "amount=" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), // ni el importe
                "currency=GBPT",
                "nonce=" + nonce,                      // un solo uso
                "expiresAt=" + SECONDS.format(expiresAt));
    }

    /**
     * SPEC §3.3. Orden de comprobación:
     * 1) ¿es una firma Ed25519 bien formada? (base64 y 64 bytes)  -> si no: MALFORMED
     * 2) ¿la firmó la clave REGISTRADA del proveedor?             -> VALID
     * 3) ¿la firmó la clave de la wallet que PIDE el dinero?      -> SELF_AUTHORISATION (el atacante)
     * 4) cualquier otra cosa                                      -> INVALID
     */
    @Override
    public RotationResult verify(String challenge, String signatureB64, String trustedPublicKeyB64, String requestedPublicKeyB64) {
        if (challenge == null || signatureB64 == null) return RotationResult.MALFORMED;
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureB64.trim());
        } catch (IllegalArgumentException notBase64) {
            return RotationResult.MALFORMED;
        }
        if (signature.length != 64) return RotationResult.MALFORMED;
        if (trustedPublicKeyB64 != null && Ed25519.verify(trustedPublicKeyB64, challenge, signature)) return RotationResult.VALID;
        if (requestedPublicKeyB64 != null && Ed25519.verify(requestedPublicKeyB64, challenge, signature)) return RotationResult.SELF_AUTHORISATION;
        return RotationResult.INVALID;
    }
}
