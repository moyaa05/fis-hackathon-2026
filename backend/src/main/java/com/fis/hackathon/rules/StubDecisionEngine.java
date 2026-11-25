package com.fis.hackathon.rules;

import com.fis.hackathon.shared.Check;
import com.fis.hackathon.shared.CheckResult;
import com.fis.hackathon.shared.Decision;
import com.fis.hackathon.shared.PaymentIntent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * PROVISIONAL (stub). Se BORRA cuando llegue DecisionEngineImpl (entrega 02).
 * Hace lo mínimo para que Antonio pueda probar verde y ámbar desde ya:
 * wallet registrada -> APPROVE; cualquier otra -> HOLD; wallet FLAGGED -> BLOCK.
 */
@Component
public class StubDecisionEngine implements DecisionEngine {

    @Override
    public Decision evaluate(PaymentIntent i) {
        if ("FLAGGED".equals(i.payeeWalletStatus())) {
            return Decision.of(List.of(new Check("RISK_LIST", "Risk list screening", CheckResult.BLOCK, 1,
                    "STUB: destination wallet is on the risk list.", Map.of("wallet", i.payeeWallet()))), i.at());
        }
        boolean registered = i.payeeWallet().equals(i.supplierTrustedWallet());
        Check check = registered
                ? new Check("NEW_BENEFICIARY_WALLET", "Key-rotation escrow", CheckResult.PASS, 1,
                    "STUB: destination is " + i.supplierName() + "'s registered wallet.", Map.of())
                : new Check("NEW_BENEFICIARY_WALLET", "Key-rotation escrow", CheckResult.HOLD, 1,
                    "STUB: destination is not " + i.supplierName() + "'s registered wallet.",
                    Map.of("trustedWallet", i.supplierTrustedWallet(), "requestedWallet", i.payeeWallet()));
        return Decision.of(List.of(check), i.at());
    }
}
