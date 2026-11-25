package com.fis.hackathon.crypto;

import com.fis.hackathon.shared.SimActor;
import com.fis.hackathon.shared.SimSignature;

/**
 * Simula "el otro lado" en la demo: el proveedor real o el atacante firmando desde SU wallet.
 * Es la ÚNICA pieza que toca claves privadas (desde demo-keys.json).
 * El endpoint POST /api/sim/sign lo expone Antonio (él tiene el pago y su escrow) y llama aquí.
 */
public interface SupplierSimulator {
    SimSignature sign(long paymentId, String challenge, String trustedWallet, String requestedWallet, SimActor as);

    /** Clave pública (SPKI base64) de cualquier wallet sembrada. La usa el seed para rellenar wallet.public_key. */
    String publicKeyOf(String walletAddress);
}
