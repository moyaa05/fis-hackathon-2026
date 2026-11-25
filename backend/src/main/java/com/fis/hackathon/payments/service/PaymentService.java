package com.fis.hackathon.payments.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis.hackathon.api.dto.ApiDtos.AuthorizeRotationRequest;
import com.fis.hackathon.api.dto.ApiDtos.CreatePaymentRequest;
import com.fis.hackathon.api.dto.ApiDtos.PaymentView;
import com.fis.hackathon.api.service.ViewMapper;
import com.fis.hackathon.api.web.ApiException;
import com.fis.hackathon.audit.AuditHasher;
import com.fis.hackathon.audit.AuditLog;
import com.fis.hackathon.crypto.KeyRotationService;
import com.fis.hackathon.crypto.SupplierSimulator;
import com.fis.hackathon.ledger.LedgerLock;
import com.fis.hackathon.ledger.domain.PartyEntity;
import com.fis.hackathon.ledger.domain.PaymentEntity;
import com.fis.hackathon.ledger.domain.WalletEntity;
import com.fis.hackathon.ledger.repository.PartyRepository;
import com.fis.hackathon.ledger.repository.PaymentRepository;
import com.fis.hackathon.ledger.repository.WalletRepository;
import com.fis.hackathon.rules.DecisionEngine;
import com.fis.hackathon.shared.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class PaymentService {

    public static final String PAYER_ID = "NORTHWIND";
    public static final String PAYER_WALLET = "tdgb:northwind:treasury";

    private final LedgerLock ledgerLock;
    private final PartyRepository partyRepo;
    private final WalletRepository walletRepo;
    private final PaymentRepository paymentRepo;
    private final DecisionEngine decisionEngine;
    private final KeyRotationService keyRotationService;
    private final SupplierSimulator supplierSimulator;
    private final AuditLog auditLog;
    private final ViewMapper viewMapper;
    private final ObjectMapper objectMapper;

    public PaymentService(
            LedgerLock ledgerLock,
            PartyRepository partyRepo,
            WalletRepository walletRepo,
            PaymentRepository paymentRepo,
            DecisionEngine decisionEngine,
            KeyRotationService keyRotationService,
            SupplierSimulator supplierSimulator,
            AuditLog auditLog,
            ViewMapper viewMapper,
            ObjectMapper objectMapper
    ) {
        this.ledgerLock = ledgerLock;
        this.partyRepo = partyRepo;
        this.walletRepo = walletRepo;
        this.paymentRepo = paymentRepo;
        this.decisionEngine = decisionEngine;
        this.keyRotationService = keyRotationService;
        this.supplierSimulator = supplierSimulator;
        this.auditLog = auditLog;
        this.viewMapper = viewMapper;
        this.objectMapper = objectMapper;
    }

    public record CreateResult(PaymentView view, boolean created) {}

    @Transactional
    public CreateResult create(CreatePaymentRequest req, String idempotencyKey) {
        ledgerLock.acquire();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Missing Idempotency-Key header.");
        }
        if (req == null || req.supplierId() == null || req.payeeWallet() == null || req.amount() == null || req.invoiceRef() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "supplierId, payeeWallet, amount and invoiceRef are required.");
        }

        String invoiceRef = req.invoiceRef().trim();
        if (invoiceRef.isEmpty() || invoiceRef.length() > 40) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "invoiceRef must be between 1 and 40 characters.");
        }
        if (idempotencyKey.trim().length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency-Key must be at most 80 characters.");
        }

        // El importe entra normalizado en el hash: "12400.0" y "12400.00" son el mismo body para la idempotencia.
        String reqHash = sha256(req.supplierId().trim() + "|" + req.payeeWallet().trim() + "|" + normalisedAmount(req.amount()) + "|" + invoiceRef);
        Optional<PaymentEntity> existing = paymentRepo.findByIdempotencyKey(idempotencyKey.trim());
        if (existing.isPresent()) {
            PaymentEntity p = existing.get();
            if (!p.getRequestHash().equals(reqHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency-Key already used with a different request body.");
            }
            return new CreateResult(toView(p), false);
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(req.amount().trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Invalid monetary amount format.");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.scale() > 2) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Amount must be greater than 0 with at most 2 decimal places.");
        }
        amount = amount.setScale(2);

        PartyEntity supplier = partyRepo.findById(req.supplierId().trim())
                .filter(party -> "SUPPLIER".equals(party.getRole()))   // el pagador o un tercero no son proveedores
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_SUPPLIER", "Supplier " + req.supplierId() + " is not a registered supplier."));
        WalletEntity payee = walletRepo.findById(req.payeeWallet().trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_WALLET", "Wallet " + req.payeeWallet() + " does not exist on the network."));
        WalletEntity payer = walletRepo.findById(PAYER_WALLET)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PAYER_MISSING", "Treasury wallet missing."));

        if (payer.getBalance().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                    "Treasury balance " + gbpt(payer.getBalance()) + " is lower than the payment amount " + gbpt(amount) + ".");
        }

        PartyEntity payeeOwner = partyRepo.findById(payee.getPartyId()).orElse(null);
        WalletEntity trustedWallet = walletRepo.findByPartyIdAndStatus(supplier.getId(), "TRUSTED").orElse(null);
        String trustedAddr = trustedWallet != null ? trustedWallet.getAddress() : "";
        String flagReason = payee.getMeta() != null && payee.getMeta().get("flagReason") != null
                ? String.valueOf(payee.getMeta().get("flagReason")) : null;

        Instant now = AuditHasher.now();
        PaymentIntent intent = new PaymentIntent(
                payer.getAddress(),
                supplier.getId(),
                supplier.getLegalName(),
                trustedAddr,
                payee.getAddress(),
                payee.getStatus(),
                payeeOwner != null ? payeeOwner.getLegalName() : payee.getPartyId(),
                ViewMapper.isKybVerified(payeeOwner),
                flagReason,
                amount,
                invoiceRef,
                now
        );

        Decision decision = decisionEngine.evaluate(intent);
        Map<String, Object> decisionMap = objectMapper.convertValue(decision, new TypeReference<>() {});

        String status = switch (decision.outcome()) {
            case APPROVE -> "SETTLED";
            case HOLD -> "HELD";
            case BLOCK -> "BLOCKED";
        };

        PaymentEntity payment = PaymentEntity.builder()
                .idempotencyKey(idempotencyKey.trim())
                .requestHash(reqHash)
                .payerWallet(payer.getAddress())
                .supplierId(supplier.getId())
                .payeeWallet(payee.getAddress())
                .amount(amount)
                .currency("GBPT")
                .invoiceRef(invoiceRef)
                .status(status)
                .decision(decisionMap)
                .createdAt(now)
                .resolvedAt("HELD".equals(status) ? null : now)
                .build();

        if ("HELD".equals(status)) {
            payment.setEscrow(Map.of("pending", true)); // satisface constraint ck_held_has_escrow antes del flush
        }

        payment = paymentRepo.saveAndFlush(payment);

        // Audit: PAYMENT_DECIDED
        List<Map<String, Object>> auditChecks = decision.checks().stream().map(c -> {
            Map<String, Object> m = new TreeMap<>();
            m.put("code", c.code());
            m.put("result", c.result().name());
            m.put("ruleVersion", c.ruleVersion());
            return m;
        }).toList();

        Map<String, Object> decidedPayload = new TreeMap<>();
        decidedPayload.put("amount", ViewMapper.money(amount));
        decidedPayload.put("checks", auditChecks);
        decidedPayload.put("currency", "GBPT");
        decidedPayload.put("initiatedBy", "treasury:" + PAYER_ID);
        decidedPayload.put("invoiceRef", payment.getInvoiceRef());
        decidedPayload.put("outcome", decision.outcome().name());
        decidedPayload.put("payeeWallet", payee.getAddress());
        decidedPayload.put("supplierId", supplier.getId());
        auditLog.append("PAYMENT_DECIDED", "payment:" + payment.getId(), "engine", decidedPayload);

        if ("SETTLED".equals(status)) {
            payer.setBalance(payer.getBalance().subtract(amount));
            payer.setUpdatedAt(now);
            payee.setBalance(payee.getBalance().add(amount));
            payee.setUpdatedAt(now);
            walletRepo.save(payer);
            walletRepo.save(payee);

            Map<String, Object> settledPayload = new TreeMap<>();
            settledPayload.put("amount", ViewMapper.money(amount));
            settledPayload.put("from", payer.getAddress());
            settledPayload.put("payerBalanceAfter", ViewMapper.money(payer.getBalance()));
            settledPayload.put("to", payee.getAddress());
            auditLog.append("FUNDS_SETTLED", "payment:" + payment.getId(), "ledger", settledPayload);

        } else if ("HELD".equals(status)) {
            payer.setBalance(payer.getBalance().subtract(amount));
            payer.setUpdatedAt(now);
            walletRepo.save(payer);

            Check holdCheck = decision.checks().stream()
                    .filter(c -> c.result() == CheckResult.HOLD)
                    .findFirst()
                    .orElse(decision.checks().getLast());
            Map<String, Object> ev = holdCheck.evidence();
            Instant expiresAt = ev.get("expiresAt") != null
                    ? Instant.parse(String.valueOf(ev.get("expiresAt")))
                    : now.plusSeconds(7200);
            int maxInvalid = ev.get("maxInvalidAttempts") instanceof Number n ? n.intValue() : 3;

            String nonce = keyRotationService.newNonce();
            String trustedPub = trustedWallet != null ? trustedWallet.getPublicKey() : "";
            String challenge = keyRotationService.buildChallenge(
                    payment.getId(), supplier.getId(), trustedAddr, payee.getAddress(), amount, nonce, expiresAt
            );
            String challengeSha = sha256(challenge);

            Map<String, Object> escrow = new LinkedHashMap<>();
            escrow.put("trustedWallet", trustedAddr);
            escrow.put("trustedPublicKey", trustedPub);
            escrow.put("requestedWallet", payee.getAddress());
            escrow.put("challenge", challenge);
            escrow.put("challengeSha256", challengeSha);
            escrow.put("expiresAt", AuditHasher.SECONDS.format(expiresAt));
            escrow.put("maxInvalidAttempts", maxInvalid);
            escrow.put("attempts", new ArrayList<>());
            escrow.put("resolution", null);

            payment.setEscrow(escrow);
            payment = paymentRepo.saveAndFlush(payment);

            Map<String, Object> heldPayload = new TreeMap<>();
            heldPayload.put("amount", ViewMapper.money(amount));
            heldPayload.put("challengeSha256", challengeSha);
            heldPayload.put("escrowExpiresAt", AuditHasher.SECONDS.format(expiresAt));
            heldPayload.put("from", payer.getAddress());
            heldPayload.put("requestedWallet", payee.getAddress());
            heldPayload.put("trustedWallet", trustedAddr);
            auditLog.append("FUNDS_HELD", "payment:" + payment.getId(), "ledger", heldPayload);
        }

        return new CreateResult(toView(payment), true);
    }

    @Transactional
    public PaymentView authorizeRotation(long paymentId, AuthorizeRotationRequest req) {
        ledgerLock.acquire();

        PaymentEntity payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment " + paymentId + " not found."));
        if (!"HELD".equals(payment.getStatus()) || payment.getEscrow() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_HELD",
                    ref(paymentId) + " is " + payment.getStatus() + "; only HELD payments accept authorisations.");
        }

        // El challenge firmado lleva expiresAt: pasado ese instante ya no se acepta ninguna firma,
        // aunque el scheduler (cada 5 s) todavia no haya devuelto los fondos.
        Instant escrowExpiresAt = Instant.parse(String.valueOf(payment.getEscrow().get("expiresAt")));
        if (!Instant.now().isBefore(escrowExpiresAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "ESCROW_EXPIRED",
                    ref(paymentId) + " escrow window expired at " + AuditHasher.SECONDS.format(escrowExpiresAt)
                            + ". Authorisation rejected; funds return to treasury automatically.");
        }

        String sig = req != null ? req.signature() : null;
        if (sig == null || sig.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_SIGNATURE", "Signature is required. No state was changed.");
        }

        Map<String, Object> escrow = new LinkedHashMap<>(payment.getEscrow());
        String challenge = String.valueOf(escrow.get("challenge"));
        String challengeSha = String.valueOf(escrow.get("challengeSha256"));
        String trustedPub = String.valueOf(escrow.get("trustedPublicKey"));
        String trustedWalletAddr = String.valueOf(escrow.get("trustedWallet"));
        String requestedWalletAddr = String.valueOf(escrow.get("requestedWallet"));

        WalletEntity requestedWallet = walletRepo.findById(requestedWalletAddr)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_WALLET", "Requested wallet not found."));

        // Re-screening al liberar: si el destino entro en la lista de riesgo mientras el pago estaba retenido,
        // ninguna firma lo libera; los fondos siguen en escrow y vuelven solos al vencer.
        if ("FLAGGED".equals(requestedWallet.getStatus())) {
            Object reason = requestedWallet.getMeta() != null ? requestedWallet.getMeta().get("flagReason") : null;
            throw new ApiException(HttpStatus.CONFLICT, "DESTINATION_FLAGGED",
                    ref(paymentId) + " cannot be authorised: destination wallet " + requestedWalletAddr + " is on the risk list"
                            + (reason != null ? " (" + reason + ")" : "") + ". Funds stay in escrow and return to treasury at expiry.");
        }

        RotationResult result = keyRotationService.verify(challenge, sig.trim(), trustedPub, requestedWallet.getPublicKey());
        if (result == RotationResult.MALFORMED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_SIGNATURE",
                    "Signature must be base64 encoding exactly 64 bytes (Ed25519). No state was changed.");
        }

        Instant now = AuditHasher.now();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts = new ArrayList<>((List<Map<String, Object>>) escrow.getOrDefault("attempts", List.of()));
        int attemptNum = attempts.size() + 1;
        int maxInvalid = escrow.get("maxInvalidAttempts") instanceof Number n ? n.intValue() : 3;
        long invalidCount = attempts.stream().filter(a -> "INVALID".equals(a.get("result"))).count()
                + (result == RotationResult.INVALID ? 1 : 0);
        String supplierName = partyRepo.findById(payment.getSupplierId()).map(PartyEntity::getLegalName).orElse(payment.getSupplierId());
        String amountText = gbpt(payment.getAmount());

        String signerPubUsed = switch (result) {
            case VALID -> trustedPub;
            case SELF_AUTHORISATION -> requestedWallet.getPublicKey();
            default -> null;
        };
        String attemptMsg = switch (result) {
            case VALID -> "Signature verified with " + supplierName + "'s registered key (" + trustedWalletAddr + "). Wallet change authorised.";
            case SELF_AUTHORISATION -> "Signature is valid, but it was produced with the key of the wallet requesting the funds ("
                    + requestedWalletAddr + "), not with " + supplierName + "'s registered key. A wallet cannot authorise itself.";
            default -> "Signature does not match " + supplierName + "'s registered key for this exact challenge. Invalid attempt "
                    + invalidCount + " of " + maxInvalid + ".";
        };

        Map<String, Object> attemptRecord = new LinkedHashMap<>();
        attemptRecord.put("at", AuditHasher.SECONDS.format(now));
        attemptRecord.put("result", result.name());
        attemptRecord.put("signerPublicKey", signerPubUsed);
        attemptRecord.put("message", attemptMsg);
        attempts.add(attemptRecord);
        escrow.put("attempts", attempts);

        if (result == RotationResult.VALID) {
            Map<String, Object> verifiedPayload = new TreeMap<>();
            verifiedPayload.put("attempt", attemptNum);
            verifiedPayload.put("challengeSha256", challengeSha);
            verifiedPayload.put("result", "VALID");
            verifiedPayload.put("signerWallet", trustedWalletAddr);
            auditLog.append("ROTATION_SIGNATURE_VERIFIED", "payment:" + paymentId, "key-rotation", verifiedPayload);

            boolean sameParty = Objects.equals(requestedWallet.getPartyId(), payment.getSupplierId());
            WalletEntity oldTrusted = sameParty
                    ? walletRepo.findByPartyIdAndStatus(payment.getSupplierId(), "TRUSTED").orElse(null) : null;
            // Si otro pago ya promovio esta misma wallet, no hay nada que rotar: solo se liberan los fondos.
            boolean alreadyRegistered = oldTrusted != null && oldTrusted.getAddress().equals(requestedWallet.getAddress());
            boolean rotate = sameParty && !alreadyRegistered;
            String resolutionType = rotate ? "RELEASED_KEY_ROTATION" : "RELEASED_ONE_OFF";

            if (rotate) {
                if (oldTrusted != null) {
                    Map<String, Object> oldMeta = new HashMap<>(oldTrusted.getMeta() != null ? oldTrusted.getMeta() : Map.of());
                    oldMeta.put("rotatedTo", requestedWallet.getAddress());
                    oldTrusted.setStatus("ROTATED");
                    oldTrusted.setMeta(oldMeta);
                    oldTrusted.setUpdatedAt(now);
                    walletRepo.saveAndFlush(oldTrusted); // flush obligatorio antes de promover la nueva (invariante I2)
                }

                Map<String, Object> newMeta = new HashMap<>(requestedWallet.getMeta() != null ? requestedWallet.getMeta() : Map.of());
                if (oldTrusted != null) newMeta.put("rotatedFrom", oldTrusted.getAddress());
                requestedWallet.setStatus("TRUSTED");
                requestedWallet.setMeta(newMeta);

                Map<String, Object> rotPayload = new TreeMap<>();
                rotPayload.put("from", oldTrusted != null ? oldTrusted.getAddress() : trustedWalletAddr);
                rotPayload.put("supplierId", payment.getSupplierId());
                rotPayload.put("to", requestedWallet.getAddress());
                auditLog.append("WALLET_ROTATED", "wallet:" + requestedWallet.getAddress(), "key-rotation", rotPayload);
            }

            requestedWallet.setBalance(requestedWallet.getBalance().add(payment.getAmount()));
            requestedWallet.setUpdatedAt(now);
            walletRepo.saveAndFlush(requestedWallet);

            Map<String, Object> resObj = new LinkedHashMap<>();
            resObj.put("type", resolutionType);
            resObj.put("at", AuditHasher.SECONDS.format(now));
            resObj.put("message", rotate
                    ? "Funds released to " + requestedWallet.getAddress() + ". It is now " + supplierName + "'s registered wallet"
                            + (oldTrusted != null ? "; " + oldTrusted.getAddress() + " retired." : ".")
                    : alreadyRegistered
                    ? amountText + " released to " + requestedWallet.getAddress() + ", already " + supplierName + "'s registered wallet."
                    : "One-off payment authorised with " + supplierName + "'s registered key. " + amountText + " released to "
                            + requestedWallet.getAddress() + "; registered wallet unchanged.");
            escrow.put("resolution", resObj);

            payment.setStatus("RELEASED");
            payment.setResolvedAt(now);
            payment.setEscrow(escrow);
            paymentRepo.saveAndFlush(payment);

            Map<String, Object> relPayload = new TreeMap<>();
            relPayload.put("amount", ViewMapper.money(payment.getAmount()));
            relPayload.put("to", requestedWallet.getAddress());
            auditLog.append("FUNDS_RELEASED", "payment:" + paymentId, "ledger", relPayload);

        } else if (result == RotationResult.SELF_AUTHORISATION) {
            Map<String, Object> rejPayload = new TreeMap<>();
            rejPayload.put("attempt", attemptNum);
            rejPayload.put("challengeSha256", challengeSha);
            rejPayload.put("result", "SELF_AUTHORISATION");
            rejPayload.put("signerPublicKey", signerPubUsed);
            auditLog.append("ROTATION_SIGNATURE_REJECTED", "payment:" + paymentId, "key-rotation", rejPayload);

            returnFundsToPayer(payment, now, "SELF_AUTHORISATION");

            Map<String, Object> flagMeta = new HashMap<>(requestedWallet.getMeta() != null ? requestedWallet.getMeta() : Map.of());
            flagMeta.put("flagReason", "Self-authorisation attempt on " + ref(paymentId));
            requestedWallet.setStatus("FLAGGED");
            requestedWallet.setMeta(flagMeta);
            requestedWallet.setUpdatedAt(now);
            walletRepo.saveAndFlush(requestedWallet);

            Map<String, Object> flagPayload = new TreeMap<>();
            flagPayload.put("reason", "Self-authorisation attempt on " + ref(paymentId));
            flagPayload.put("wallet", requestedWallet.getAddress());
            auditLog.append("WALLET_FLAGGED", "wallet:" + requestedWallet.getAddress(), "key-rotation", flagPayload);

            Map<String, Object> resObj = new LinkedHashMap<>();
            resObj.put("type", "BLOCKED_SELF_AUTHORISATION");
            resObj.put("at", AuditHasher.SECONDS.format(now));
            resObj.put("message", "Payment blocked. " + amountText + " returned to treasury; " + requestedWallet.getAddress() + " added to the risk list.");
            escrow.put("resolution", resObj);

            payment.setStatus("BLOCKED");
            payment.setResolvedAt(now);
            payment.setEscrow(escrow);
            paymentRepo.saveAndFlush(payment);

        } else {
            // INVALID
            Map<String, Object> rejPayload = new TreeMap<>();
            rejPayload.put("attempt", attemptNum);
            rejPayload.put("challengeSha256", challengeSha);
            rejPayload.put("result", "INVALID");
            auditLog.append("ROTATION_SIGNATURE_REJECTED", "payment:" + paymentId, "key-rotation", rejPayload);

            if (invalidCount >= maxInvalid) {
                returnFundsToPayer(payment, now, "MAX_INVALID_ATTEMPTS");
                Map<String, Object> resObj = new LinkedHashMap<>();
                resObj.put("type", "BLOCKED_MAX_ATTEMPTS");
                resObj.put("at", AuditHasher.SECONDS.format(now));
                resObj.put("message", "Payment blocked after " + invalidCount + " invalid signature attempts. " + amountText + " returned to treasury.");
                escrow.put("resolution", resObj);
                payment.setStatus("BLOCKED");
                payment.setResolvedAt(now);
            }
            payment.setEscrow(escrow);
            paymentRepo.saveAndFlush(payment);
        }

        return toView(payment);
    }

    @Transactional
    public PaymentView expirePayment(long paymentId, boolean simulated) {
        ledgerLock.acquire();

        PaymentEntity payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment " + paymentId + " not found."));
        if (!"HELD".equals(payment.getStatus()) || payment.getEscrow() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_HELD",
                    ref(paymentId) + " is " + payment.getStatus() + "; only HELD payments can expire.");
        }

        Instant now = AuditHasher.now();
        Map<String, Object> escrow = new LinkedHashMap<>(payment.getEscrow());
        String originalExpiresAt = String.valueOf(escrow.get("expiresAt"));
        if (simulated) {
            escrow.put("expiresAt", AuditHasher.SECONDS.format(now));
        }

        String actor = simulated ? "demo" : "scheduler";
        Map<String, Object> expPayload = new TreeMap<>();
        expPayload.put("expiresAt", originalExpiresAt);
        expPayload.put("simulated", simulated);
        auditLog.append("ESCROW_EXPIRED", "payment:" + paymentId, actor, expPayload);

        returnFundsToPayer(payment, now, "EXPIRED");

        Map<String, Object> resObj = new LinkedHashMap<>();
        resObj.put("type", "RETURNED_EXPIRED");
        resObj.put("at", AuditHasher.SECONDS.format(now));
        resObj.put("message", "No authorisation before expiry. " + gbpt(payment.getAmount()) + " returned to treasury automatically.");
        escrow.put("resolution", resObj);

        payment.setStatus("REVERTED");
        payment.setResolvedAt(now);
        payment.setEscrow(escrow);
        paymentRepo.saveAndFlush(payment);

        return toView(payment);
    }

    @Transactional(readOnly = true)
    public SimSignature simulateSign(long paymentId, SimActor actor) {
        if (actor == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Field 'as' is required.");
        }
        PaymentEntity payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment " + paymentId + " not found."));
        if (!"HELD".equals(payment.getStatus()) || payment.getEscrow() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_HELD",
                    ref(paymentId) + " is " + payment.getStatus() + "; only HELD payments can be signed.");
        }
        Map<String, Object> escrow = payment.getEscrow();
        String challenge = String.valueOf(escrow.get("challenge"));
        String trustedWallet = String.valueOf(escrow.get("trustedWallet"));
        String requestedWallet = String.valueOf(escrow.get("requestedWallet"));
        return supplierSimulator.sign(paymentId, challenge, trustedWallet, requestedWallet, actor);
    }

    private void returnFundsToPayer(PaymentEntity payment, Instant now, String reason) {
        WalletEntity payer = walletRepo.findById(payment.getPayerWallet()).orElseThrow();
        payer.setBalance(payer.getBalance().add(payment.getAmount()));
        payer.setUpdatedAt(now);
        walletRepo.saveAndFlush(payer);

        Map<String, Object> retPayload = new TreeMap<>();
        retPayload.put("amount", ViewMapper.money(payment.getAmount()));
        retPayload.put("reason", reason);
        retPayload.put("to", payer.getAddress());
        auditLog.append("FUNDS_RETURNED", "payment:" + payment.getId(), "ledger", retPayload);
    }

    public PaymentView toView(PaymentEntity p) {
        PartyEntity supplier = partyRepo.findById(p.getSupplierId()).orElse(null);
        WalletEntity payee = walletRepo.findById(p.getPayeeWallet()).orElse(null);
        PartyEntity payeeOwner = payee != null ? partyRepo.findById(payee.getPartyId()).orElse(null) : null;
        return viewMapper.toPaymentView(p, supplier, payee, payeeOwner);
    }

    private static String ref(long paymentId) {
        return String.format("PAY-%04d", paymentId);
    }

    /** "80,000.00 GBPT" para los mensajes en ingles que lee el jurado. */
    private static String gbpt(BigDecimal amount) {
        return String.format(Locale.UK, "%,.2f GBPT", amount);
    }

    private static String normalisedAmount(String raw) {
        try {
            return new BigDecimal(raw.trim()).setScale(2).toPlainString();
        } catch (ArithmeticException | NumberFormatException notAValidAmount) {
            return raw.trim();   // se rechazara despues con 422 INVALID_AMOUNT
        }
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
