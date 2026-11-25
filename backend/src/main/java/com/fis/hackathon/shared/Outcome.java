package com.fis.hackathon.shared;

/**
 * Veredicto final del motor para un pago.
 * El orden importa: va de menos a más grave (APPROVE < HOLD < BLOCK).
 */
public enum Outcome {
    APPROVE, HOLD, BLOCK;

    /** Devuelve el más grave de los dos. */
    public Outcome max(Outcome other) {
        return other.ordinal() > this.ordinal() ? other : this;
    }
}
