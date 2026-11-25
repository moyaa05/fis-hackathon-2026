package com.fis.hackathon.api.service;

import com.fis.hackathon.api.dto.ApiDtos.PaymentView;
import com.fis.hackathon.api.dto.ApiDtos.WalletView;
import com.fis.hackathon.ledger.domain.PartyEntity;
import com.fis.hackathon.ledger.domain.PaymentEntity;
import com.fis.hackathon.ledger.domain.WalletEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Component
public class ViewMapper {

    public static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static boolean isKybVerified(PartyEntity party) {
        if (party == null || party.getKyb() == null) return false;
        Object v = party.getKyb().get("verified");
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    public WalletView toWalletView(WalletEntity w, PartyEntity owner) {
        Map<String, Object> meta = w.getMeta() == null ? Map.of() : w.getMeta();
        return new WalletView(
                w.getAddress(),
                w.getPartyId(),
                owner != null ? owner.getLegalName() : w.getPartyId(),
                w.getBank(),
                w.getStatus(),
                money(w.getBalance()),
                isKybVerified(owner),
                str(meta.get("label")),
                str(meta.get("flagReason")),
                str(meta.get("rotatedFrom")),
                str(meta.get("rotatedTo")),
                w.getPublicKey()
        );
    }

    public PaymentView toPaymentView(PaymentEntity p, PartyEntity supplier, WalletEntity payeeWallet, PartyEntity payeeOwner) {
        return new PaymentView(
                p.getId(),
                String.format("PAY-%04d", p.getId()),
                p.getSupplierId(),
                supplier != null ? supplier.getLegalName() : p.getSupplierId(),
                p.getPayeeWallet(),
                payeeWallet != null ? payeeWallet.getStatus() : "UNVERIFIED",
                payeeOwner != null ? payeeOwner.getLegalName() : "Unknown",
                isKybVerified(payeeOwner),
                money(p.getAmount()),
                p.getCurrency(),
                p.getInvoiceRef(),
                p.getStatus(),
                p.getDecision(),
                p.getEscrow(),
                p.getCreatedAt(),
                p.getResolvedAt()
        );
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
