package com.fis.hackathon.shared;

/** Respuesta de POST /api/sim/sign (SPEC §5.1). */
public record SimSignature(
        long paymentId,
        SimActor as,
        String signerWallet,
        String signerPublicKey,
        String signedMessage,
        String signature,
        String explanation
) {}
