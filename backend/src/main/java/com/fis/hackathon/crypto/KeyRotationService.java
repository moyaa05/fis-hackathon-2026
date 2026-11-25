package com.fis.hackathon.crypto;

import com.fis.hackathon.shared.RotationResult;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ENCHUFE entre Antonio y Alejandro: todo lo criptográfico del escrow.
 * Antonio lo llama al retener (newNonce + buildChallenge) y al recibir una firma (verify).
 */
public interface KeyRotationService {

    /** 16 bytes aleatorios en base64url sin padding (SPEC §3.1). */
    String newNonce();

    /** Construye el texto EXACTO que el proveedor tiene que firmar (SPEC §3.1). */
    String buildChallenge(long paymentId, String supplierId, String trustedWallet, String requestedWallet,
                          BigDecimal amount, String nonce, Instant expiresAt);

    /**
     * Clasifica una firma (SPEC §3.3). NUNCA lanza excepción: cualquier entrada rara es MALFORMED o INVALID.
     * trustedPublicKeyB64 = snapshot guardado en el escrow al retener (no la clave actual de la wallet).
     */
    RotationResult verify(String challenge, String signatureB64,
                          String trustedPublicKeyB64, String requestedPublicKeyB64);
}
