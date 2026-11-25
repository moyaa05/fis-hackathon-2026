package com.fis.hackathon.shared;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Todo lo que el motor necesita saber de un pago ANTES de decidir.
 * Lo rellena PaymentService (Antonio) a partir de sus entidades; así el motor (Alejandro)
 * no depende de las entidades JPA y se puede probar sin base de datos.
 */
public record PaymentIntent(
        String payerWallet,           // 'tdgb:northwind:treasury'
        String supplierId,            // 'ACME'
        String supplierName,          // 'Acme Components Ltd'
        String supplierTrustedWallet, // wallet TRUSTED actual del proveedor ('tdgb:acme:op-01')
        String payeeWallet,           // wallet a la que se quiere pagar
        String payeeWalletStatus,     // TRUSTED | UNVERIFIED | ROTATED | FLAGGED
        String payeeOwnerName,        // dueño legal de la wallet destino ('Acme Trading Ltd')
        boolean payeeKybVerified,
        String payeeFlagReason,       // null si no está en lista de riesgo
        BigDecimal amount,
        String invoiceRef,
        Instant at                    // momento de la decisión (ya truncado a milisegundos)
) {}
