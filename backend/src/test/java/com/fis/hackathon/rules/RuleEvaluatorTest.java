package com.fis.hackathon.rules;

import com.fis.hackathon.shared.CheckResult;
import com.fis.hackathon.shared.Decision;
import com.fis.hackathon.shared.Outcome;
import com.fis.hackathon.shared.PaymentIntent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Los 4 caminos del motor, con los datos del seed de la demo (SPEC §6). */
class RuleEvaluatorTest {

    private static final Instant AT = Instant.parse("2026-11-25T21:02:41.530Z");
    private final List<RuleDef> rules = RuleCatalog.defaults(AT);

    private static PaymentIntent pay(String payee, String status, String owner, String flag, String amount) {
        return new PaymentIntent("tdgb:northwind:treasury", "ACME", "Acme Components Ltd", "tdgb:acme:op-01",
                payee, status, owner, true, flag, new BigDecimal(amount), "INV-AC-1147", AT);
    }

    @Test
    void registeredWalletIsApproved() {
        Decision d = RuleEvaluator.evaluate(pay("tdgb:acme:op-01", "TRUSTED", "Acme Components Ltd", null, "12400.00"), rules, 0);
        assertEquals(Outcome.APPROVE, d.outcome());
        assertEquals(3, d.checks().size());
    }

    @Test
    void newWalletIsHeldWithEscrowParameters() {
        Decision d = RuleEvaluator.evaluate(pay("tdgb:acmetrading:7731", "UNVERIFIED", "Acme Trading Ltd", null, "80000.00"), rules, 0);
        assertEquals(Outcome.HOLD, d.outcome());
        var hold = d.checks().get(2);
        assertEquals(CheckResult.HOLD, hold.result());
        assertEquals("2026-11-25T23:02:41Z", hold.evidence().get("expiresAt"));
        assertEquals(120, hold.evidence().get("holdMinutes"));
        assertTrue(hold.message().contains("Automatic return at 23:02 UTC"));
    }

    @Test
    void flaggedWalletIsBlocked() {
        Decision d = RuleEvaluator.evaluate(pay("tdgb:acmetrading:7731", "FLAGGED", "Acme Trading Ltd",
                "Self-authorisation attempt on PAY-0003", "30000.00"), rules, 2);
        assertEquals(Outcome.BLOCK, d.outcome());
        assertEquals(CheckResult.BLOCK, d.checks().get(0).result());
    }

    @Test
    void velocityBlocksTheFourthAttempt() {
        Decision d = RuleEvaluator.evaluate(pay("tdgb:acmetrading:7731", "UNVERIFIED", "Acme Trading Ltd", null, "500.00"), rules, 3);
        assertEquals(Outcome.BLOCK, d.outcome());
        assertEquals(CheckResult.BLOCK, d.checks().get(1).result());
    }

    @Test
    void disabledRuleIsSkippedAndShown() {
        List<RuleDef> modified = new ArrayList<>();
        for (RuleDef r : rules) {
            modified.add(r.code().equals(RuleCatalog.NEW_BENEFICIARY_WALLET)
                    ? new RuleDef(r.code(), r.name(), r.description(), r.outcome(), false, r.priority(), r.params(), 2, "compliance:test", AT)
                    : r);
        }
        Decision d = RuleEvaluator.evaluate(pay("tdgb:acme:op-02", "UNVERIFIED", "Acme Components Ltd", null, "45000.00"), modified, 0);
        assertEquals(Outcome.APPROVE, d.outcome());
        assertEquals(CheckResult.SKIPPED, d.checks().get(2).result());
    }
}
