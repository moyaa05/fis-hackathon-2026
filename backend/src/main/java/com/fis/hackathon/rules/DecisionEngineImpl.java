package com.fis.hackathon.rules;

import com.fis.hackathon.shared.Decision;
import com.fis.hackathon.shared.PaymentIntent;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Implementación REAL del motor (sustituye a StubDecisionEngine).
 * Solo lee: las reglas vigentes y el contador de intentos. Decide RuleEvaluator.
 */
@Component
public class DecisionEngineImpl implements DecisionEngine {

    private final RuleRepository rules;
    private final UnverifiedAttemptsQuery attempts;

    public DecisionEngineImpl(RuleRepository rules, UnverifiedAttemptsQuery attempts) {
        this.rules = rules;
        this.attempts = attempts;
    }

    @Override
    public Decision evaluate(PaymentIntent intent) {
        List<RuleDef> current = rules.findAllByOrderByPriorityAsc().stream().map(RuleEntity::toDef).toList();
        int window = current.stream()
                .filter(r -> RuleCatalog.VELOCITY_UNVERIFIED.equals(r.code()))
                .findFirst()
                .map(r -> RuleParams.intParam(r.params(), "windowMinutes", 30))
                .orElse(30);
        int previous = attempts.count(intent.payerWallet(), intent.supplierId(),
                intent.at().minus(window, ChronoUnit.MINUTES));
        return RuleEvaluator.evaluate(intent, current, previous);
    }
}
