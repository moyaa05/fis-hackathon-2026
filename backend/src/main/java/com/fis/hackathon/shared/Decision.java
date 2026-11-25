package com.fis.hackathon.shared;

import java.time.Instant;
import java.util.List;

/**
 * Decisión completa del motor: veredicto + TODAS las reglas evaluadas (también las que pasaron),
 * para que la explicación muestre qué se comprobó y no solo qué falló.
 */
public record Decision(Outcome outcome, Instant evaluatedAt, List<Check> checks) {

    /** Regla de combinación (SPEC §2): BLOCK si alguna bloquea; si no HOLD si alguna retiene; si no APPROVE. */
    public static Decision of(List<Check> checks, Instant evaluatedAt) {
        Outcome outcome = Outcome.APPROVE;
        for (Check c : checks) {
            if (c.result() == CheckResult.BLOCK) outcome = outcome.max(Outcome.BLOCK);
            else if (c.result() == CheckResult.HOLD) outcome = outcome.max(Outcome.HOLD);
        }
        return new Decision(outcome, evaluatedAt, List.copyOf(checks));
    }
}
