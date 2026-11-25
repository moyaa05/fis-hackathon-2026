package com.fis.hackathon.rules;

import com.fis.hackathon.shared.Check;
import com.fis.hackathon.shared.CheckResult;
import com.fis.hackathon.shared.Decision;
import com.fis.hackathon.shared.PaymentIntent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El "cerebro" de las reglas, sin base de datos (se prueba en memoria). SPEC §2.
 * Recibe: el pago, las reglas y cuántos intentos previos a wallets no verificadas hubo en la ventana.
 * Devuelve: una Decision con TODAS las reglas evaluadas y su explicación en inglés.
 *
 * Para el escrow, la regla NEW_BENEFICIARY_WALLET deja en su evidence:
 *   holdMinutes, maxInvalidAttempts y expiresAt (yyyy-MM-ddTHH:mm:ssZ)  -> Antonio los usa al retener.
 */
public final class RuleEvaluator {

    private RuleEvaluator() {}

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    public static Decision evaluate(PaymentIntent i, List<RuleDef> rules, int previousUnverifiedAttempts) {
        List<RuleDef> ordered = new ArrayList<>(rules);
        ordered.sort(Comparator.comparingInt(RuleDef::priority));
        boolean toUnverified = i.supplierTrustedWallet() == null || !i.payeeWallet().equals(i.supplierTrustedWallet());
        String trusted = i.supplierTrustedWallet() == null ? "(none)" : i.supplierTrustedWallet();

        List<Check> checks = new ArrayList<>();
        for (RuleDef r : ordered) {
            if (!r.enabled()) {
                checks.add(new Check(r.code(), r.name(), CheckResult.SKIPPED, r.version(),
                        "Rule disabled by compliance (v" + r.version() + ").", Map.of()));
                continue;
            }
            switch (r.code()) {
                case RuleCatalog.RISK_LIST -> checks.add(riskList(r, i));
                case RuleCatalog.VELOCITY_UNVERIFIED -> checks.add(velocity(r, i, toUnverified, previousUnverifiedAttempts));
                case RuleCatalog.NEW_BENEFICIARY_WALLET -> checks.add(newBeneficiary(r, i, toUnverified, trusted));
                default -> { /* regla desconocida: se ignora */ }
            }
        }
        return Decision.of(checks, i.at());
    }

    private static Check riskList(RuleDef r, PaymentIntent i) {
        if ("FLAGGED".equals(i.payeeWalletStatus())) {
            String reason = i.payeeFlagReason() == null ? "risk list" : i.payeeFlagReason();
            return new Check(r.code(), r.name(), CheckResult.BLOCK, r.version(),
                    "Destination wallet " + i.payeeWallet() + " is on the risk list (" + reason + "). Blocked: no funds moved.",
                    ev("wallet", i.payeeWallet(), "flagReason", reason));
        }
        return new Check(r.code(), r.name(), CheckResult.PASS, r.version(), "Destination wallet is not on the risk list.", Map.of());
    }

    private static Check velocity(RuleDef r, PaymentIntent i, boolean toUnverified, int previous) {
        int max = RuleParams.intParam(r.params(), "maxAttempts", 3);
        int window = RuleParams.intParam(r.params(), "windowMinutes", 30);
        int n = previous + (toUnverified ? 1 : 0);
        Map<String, Object> evidence = ev("attempts", n, "maxAttempts", max, "windowMinutes", window);
        if (toUnverified && n > max) {
            return new Check(r.code(), r.name(), CheckResult.BLOCK, r.version(),
                    n + " payment attempts to unverified wallets for " + i.supplierName() + " in the last " + window
                            + " min (limit " + max + "). Possible BEC campaign: blocked.", evidence);
        }
        return new Check(r.code(), r.name(), CheckResult.PASS, r.version(),
                n + " of " + max + " allowed payment attempts to unverified wallets for " + i.supplierName()
                        + " in the last " + window + " min.", evidence);
    }

    private static Check newBeneficiary(RuleDef r, PaymentIntent i, boolean toUnverified, String trusted) {
        int holdMinutes = RuleParams.intParam(r.params(), "holdMinutes", 120);
        int maxInvalid = RuleParams.intParam(r.params(), "maxInvalidAttempts", 3);
        BigDecimal minAmount = RuleParams.moneyParam(r.params(), "minAmount", BigDecimal.ZERO);
        if (!toUnverified) {
            return new Check(r.code(), r.name(), CheckResult.PASS, r.version(),
                    "Destination is " + i.supplierName() + "'s registered wallet.", ev("trustedWallet", trusted));
        }
        if (i.amount().compareTo(minAmount) < 0) {
            return new Check(r.code(), r.name(), CheckResult.PASS, r.version(),
                    "Below the " + minAmount.toPlainString() + " GBPT escrow threshold.", ev("trustedWallet", trusted));
        }
        Instant expiresAt = i.at().plus(holdMinutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        String kyb = i.payeeKybVerified() ? "KYB verified" : "KYB NOT verified";
        return new Check(r.code(), r.name(), CheckResult.HOLD, r.version(),
                "Destination wallet " + i.payeeWallet() + " (owner: " + i.payeeOwnerName() + ", " + kyb + ") is not "
                        + i.supplierName() + "'s registered wallet " + trusted + ". Funds locked in escrow until "
                        + i.supplierName() + " authorises the change with its registered key. Automatic return at "
                        + HHMM.format(expiresAt) + " UTC.",
                ev("trustedWallet", trusted, "requestedWallet", i.payeeWallet(),
                        "requestedWalletOwner", i.payeeOwnerName(), "requestedWalletKybVerified", i.payeeKybVerified(),
                        "holdMinutes", holdMinutes, "maxInvalidAttempts", maxInvalid, "expiresAt", SECONDS.format(expiresAt)));
    }

    private static Map<String, Object> ev(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }
}
