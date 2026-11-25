package com.fis.hackathon.rules;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Las 3 reglas de la demo con sus valores por defecto (SPEC §2). Las usa RuleSeeder en cada reset. */
public final class RuleCatalog {

    private RuleCatalog() {}

    public static final String RISK_LIST = "RISK_LIST";
    public static final String VELOCITY_UNVERIFIED = "VELOCITY_UNVERIFIED";
    public static final String NEW_BENEFICIARY_WALLET = "NEW_BENEFICIARY_WALLET";

    public static List<RuleDef> defaults(Instant now) {
        return List.of(
                new RuleDef(RISK_LIST, "Risk list screening",
                        "Block payments to wallets on the sanctions / risk list.",
                        "BLOCK", true, 1, map(), 1, "system", now),
                new RuleDef(VELOCITY_UNVERIFIED, "Unverified-wallet velocity",
                        "Block when one supplier receives too many payment attempts to unverified wallets in a short window (BEC campaign pattern).",
                        "BLOCK", true, 2, map("maxAttempts", 3, "windowMinutes", 30), 1, "system", now),
                new RuleDef(NEW_BENEFICIARY_WALLET, "Key-rotation escrow",
                        "Hold in programmable escrow any payment to a wallet that is not the supplier's registered wallet, until the supplier authorises the change with its registered key.",
                        "HOLD", true, 3, map("holdMinutes", 120, "minAmount", "0.00", "maxInvalidAttempts", 3), 1, "system", now));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
