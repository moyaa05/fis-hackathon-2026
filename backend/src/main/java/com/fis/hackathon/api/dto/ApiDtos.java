package com.fis.hackathon.api.dto;

import com.fis.hackathon.audit.AuditReadService;
import com.fis.hackathon.rules.RuleService;
import com.fis.hackathon.shared.SimActor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApiDtos {
    private ApiDtos() {}

    public record AppState(
            Instant serverTime,
            PayerSummary payer,
            LedgerSummary ledger,
            MetricsSummary metrics,
            List<SupplierSummary> suppliers,
            List<WalletView> wallets,
            List<PaymentView> payments,
            List<RuleService.RuleView> rules,
            AuditReadService.AuditSummary audit
    ) {}

    public record PayerSummary(String partyId, String legalName, String wallet, String balance, String currency) {}
    public record LedgerSummary(String currency, String totalSupply, String payerBalance, String inEscrow, String supplierBalances, boolean conserved) {}
    public record MetricsSummary(String protectedAmount, int protectedCount, Map<String, Integer> byStatus) {}
    public record SupplierSummary(String partyId, String legalName, String trustedWallet, Map<String, Object> kyb) {}

    public record WalletView(
            String address, String partyId, String ownerName, String bank,
            String status, String balance, boolean kybVerified, String label,
            String flagReason, String rotatedFrom, String rotatedTo
    ) {}

    public record PaymentView(
            long id, String ref, String supplierId, String supplierName,
            String payeeWallet, String payeeWalletStatus, String payeeWalletOwner, boolean payeeKybVerified,
            String amount, String currency, String invoiceRef, String status,
            Map<String, Object> decision, Map<String, Object> escrow,
            Instant createdAt, Instant resolvedAt
    ) {}

    public record CreatePaymentRequest(String supplierId, String payeeWallet, String amount, String invoiceRef) {}
    public record AuthorizeRotationRequest(String signature, String submittedBy) {}
    public record SimSignRequest(long paymentId, SimActor as) {}
    public record ResetResult(boolean ok, Instant resetAt, String headHash, int payments, long auditLength) {}
}
