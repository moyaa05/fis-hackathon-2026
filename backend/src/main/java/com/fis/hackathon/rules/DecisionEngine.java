package com.fis.hackathon.rules;

import com.fis.hackathon.shared.Decision;
import com.fis.hackathon.shared.PaymentIntent;

/**
 * ENCHUFE entre Antonio y Alejandro.
 * Antonio lo llama desde PaymentService.create(); Alejandro lo implementa.
 * Lee reglas y cuenta intentos, pero NUNCA escribe en base de datos.
 */
public interface DecisionEngine {
    Decision evaluate(PaymentIntent intent);
}
