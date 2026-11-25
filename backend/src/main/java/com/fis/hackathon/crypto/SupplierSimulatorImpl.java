package com.fis.hackathon.crypto;

import com.fis.hackathon.shared.SimActor;
import com.fis.hackathon.shared.SimSignature;
import org.springframework.stereotype.Component;

/**
 * Implementación REAL del simulador (sustituye a StubSupplierSimulator).
 * Hace de "el otro lado" en la demo:
 *  - LEGIT_SUPPLIER: el proveedor real firma con la clave de su wallet registrada  -> el banco dirá VALID.
 *  - ATTACKER: el atacante firma con la clave de la wallet NUEVA (la suya)          -> SELF_AUTHORISATION.
 *  - TAMPERED: firma legítima pero sobre un challenge con otro destino            -> INVALID.
 */
@Component
public class SupplierSimulatorImpl implements SupplierSimulator {

    static final String TAMPERED_DESTINATION = "tdgb:attacker:0000";
    private final DemoKeyStore keyStore;

    public SupplierSimulatorImpl(DemoKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @Override
    public SimSignature sign(long paymentId, String challenge, String trustedWallet, String requestedWallet, SimActor as) {
        String signer;
        String message;
        String explanation;
        switch (as) {
            case LEGIT_SUPPLIER -> {
                signer = trustedWallet;
                message = challenge;
                explanation = "The supplier signs the challenge with the key of its registered wallet ("
                        + trustedWallet + "), held in its own wallet/HSM.";
            }
            case ATTACKER -> {
                signer = requestedWallet;
                message = challenge;
                explanation = "The attacker controls the email thread and the new wallet (" + requestedWallet
                        + "), so he signs with the new wallet's key. The signature is cryptographically valid, but made with the wrong key.";
            }
            case TAMPERED -> {
                signer = trustedWallet;
                message = challenge.replace("requestedWallet=" + requestedWallet, "requestedWallet=" + TAMPERED_DESTINATION);
                explanation = "A legitimate signature over an altered challenge (different destination wallet). Simulates replay / substitution.";
            }
            default -> throw new IllegalArgumentException("Unknown actor " + as);
        }
        String signature = Ed25519.sign(keyStore.privateKey(signer), message);
        return new SimSignature(paymentId, as, signer, keyStore.publicKey(signer), message, signature, explanation);
    }

    @Override
    public String publicKeyOf(String walletAddress) {
        return keyStore.publicKey(walletAddress);
    }
}
