package com.fis.hackathon.crypto;

import com.fis.hackathon.shared.SimActor;
import com.fis.hackathon.shared.SimSignature;
import org.springframework.stereotype.Component;

/**
 * PROVISIONAL (stub). Se BORRA cuando llegue SupplierSimulatorImpl (entrega 02).
 * Devuelve las firmas falsas "LEGIT"/"ATTACKER" que entiende StubKeyRotationService.
 */
@Component
public class StubSupplierSimulator implements SupplierSimulator {

    @Override
    public SimSignature sign(long paymentId, String challenge, String trustedWallet, String requestedWallet, SimActor as) {
        String signer = as == SimActor.ATTACKER ? requestedWallet : trustedWallet;
        String signature = switch (as) {
            case LEGIT_SUPPLIER -> "LEGIT";
            case ATTACKER -> "ATTACKER";
            case TAMPERED -> "TAMPERED";
        };
        return new SimSignature(paymentId, as, signer, "STUB-PUBLIC-KEY", challenge, signature, "STUB signature");
    }

    @Override
    public String publicKeyOf(String walletAddress) {
        return "STUB-PUBLIC-KEY-" + walletAddress;
    }
}
