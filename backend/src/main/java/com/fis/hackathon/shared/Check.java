package com.fis.hackathon.shared;

import java.util.Map;

/**
 * Lo que dice UNA regla sobre UN pago. Es lo que la UI pinta en la "tarjeta de decisión".
 * message va en inglés (jurado); evidence son los datos que justifican el resultado.
 */
public record Check(
        String code,
        String name,
        CheckResult result,
        int ruleVersion,
        String message,
        Map<String, Object> evidence
) {
    public Check {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
