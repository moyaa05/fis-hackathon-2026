package com.fis.hackathon.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lectura y validación de los parámetros de las reglas (SPEC §5.2, PATCH /api/rules).
 * normalize() deja cada valor con su tipo correcto (enteros como Integer, dinero como "0.00"),
 * para que el JSON del audit sea siempre idéntico.
 */
public final class RuleParams {

    private RuleParams() {}

    public static final class InvalidParamsException extends RuntimeException {
        public InvalidParamsException(String message) { super(message); }
    }

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            RuleCatalog.RISK_LIST, Set.of(),
            RuleCatalog.VELOCITY_UNVERIFIED, Set.of("maxAttempts", "windowMinutes"),
            RuleCatalog.NEW_BENEFICIARY_WALLET, Set.of("holdMinutes", "minAmount", "maxInvalidAttempts"));

    public static int intParam(Map<String, Object> params, String key, int fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) return Integer.parseInt(s.trim());
        return fallback;
    }

    public static BigDecimal moneyParam(Map<String, Object> params, String key, BigDecimal fallback) {
        Object v = params == null ? null : params.get(key);
        if (v == null) return fallback;
        return new BigDecimal(v.toString().trim()).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Valida y normaliza. Lanza InvalidParamsException con un mensaje en inglés para la UI. */
    public static Map<String, Object> normalize(String ruleCode, Map<String, Object> params) {
        Set<String> allowed = ALLOWED.getOrDefault(ruleCode, Set.of());
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String k = e.getKey();
            if (!allowed.contains(k)) throw new InvalidParamsException("Unknown parameter '" + k + "' for rule " + ruleCode + ".");
            switch (k) {
                case "maxAttempts" -> out.put(k, range(k, e.getValue(), 1, 20));
                case "windowMinutes" -> out.put(k, range(k, e.getValue(), 1, 1440));
                case "holdMinutes" -> out.put(k, range(k, e.getValue(), 1, 10080));
                case "maxInvalidAttempts" -> out.put(k, range(k, e.getValue(), 1, 10));
                case "minAmount" -> {
                    BigDecimal d;
                    try {
                        d = new BigDecimal(String.valueOf(e.getValue()).trim()).setScale(2, RoundingMode.UNNECESSARY);
                    } catch (RuntimeException ex) {
                        throw new InvalidParamsException("minAmount must be an amount with at most 2 decimals.");
                    }
                    if (d.signum() < 0) throw new InvalidParamsException("minAmount must be >= 0.");
                    out.put(k, d.toPlainString());
                }
                default -> throw new InvalidParamsException("Unknown parameter '" + k + "'.");
            }
        }
        return out;
    }

    private static Integer range(String key, Object raw, int min, int max) {
        int v;
        try {
            if (raw instanceof Number n) {
                if (n.doubleValue() != Math.rint(n.doubleValue())) throw new NumberFormatException();
                v = n.intValue();
            } else {
                v = Integer.parseInt(String.valueOf(raw).trim());
            }
        } catch (NumberFormatException ex) {
            throw new InvalidParamsException(key + " must be an integer.");
        }
        if (v < min || v > max) throw new InvalidParamsException(key + " must be between " + min + " and " + max + ".");
        return v;
    }
}
