package com.fis.hackathon.api.service;

import com.fis.hackathon.api.dto.ApiDtos.*;
import com.fis.hackathon.audit.AuditHasher;
import com.fis.hackathon.audit.AuditReadService;
import com.fis.hackathon.ledger.domain.PartyEntity;
import com.fis.hackathon.ledger.domain.PaymentEntity;
import com.fis.hackathon.ledger.domain.WalletEntity;
import com.fis.hackathon.ledger.repository.PartyRepository;
import com.fis.hackathon.ledger.repository.PaymentRepository;
import com.fis.hackathon.ledger.repository.WalletRepository;
import com.fis.hackathon.payments.service.PaymentService;
import com.fis.hackathon.rules.RuleService;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StateQueryService {

    private final PartyRepository partyRepo;
    private final WalletRepository walletRepo;
    private final PaymentRepository paymentRepo;
    private final RuleService ruleService;
    private final AuditReadService auditReadService;
    private final ViewMapper viewMapper;
    private final JdbcTemplate jdbc;

    /** Una sola sentencia = una sola foto de la BD: saldos y escrow siempre cuadran entre si, aunque haya un commit en medio. */
    private static final String LEDGER_SQL = """
            SELECT (SELECT COALESCE(SUM(balance), 0) FROM wallet  WHERE address =  ?)      AS payer_balance,
                   (SELECT COALESCE(SUM(balance), 0) FROM wallet  WHERE address <> ?)      AS supplier_balances,
                   (SELECT COALESCE(SUM(amount),  0) FROM payment WHERE status = 'HELD')   AS in_escrow
            """;
    private static final String METRICS_SQL = "SELECT status, COUNT(*) AS n, COALESCE(SUM(amount), 0) AS total FROM payment GROUP BY status";

    public StateQueryService(
            PartyRepository partyRepo,
            WalletRepository walletRepo,
            PaymentRepository paymentRepo,
            RuleService ruleService,
            AuditReadService auditReadService,
            ViewMapper viewMapper,
            JdbcTemplate jdbc
    ) {
        this.partyRepo = partyRepo;
        this.walletRepo = walletRepo;
        this.paymentRepo = paymentRepo;
        this.ruleService = ruleService;
        this.auditReadService = auditReadService;
        this.viewMapper = viewMapper;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AppState getState() {
        Map<String, PartyEntity> parties = partyRepo.findAll().stream()
                .collect(Collectors.toMap(PartyEntity::getId, Function.identity()));
        List<WalletEntity> wallets = walletRepo.findAllByOrderByPartyIdAscAddressAsc();
        Map<String, WalletEntity> walletMap = wallets.stream()
                .collect(Collectors.toMap(WalletEntity::getAddress, Function.identity()));

        List<PaymentEntity> allPayments = paymentRepo.findAllByOrderByIdDesc(PageRequest.of(0, 50));

        PartyEntity payerParty = parties.get(PaymentService.PAYER_ID);

        // Totales del ledger: agregados en BD sobre TODOS los pagos (la lista de arriba solo trae los 50 ultimos).
        Map<String, Object> ledger = jdbc.queryForMap(LEDGER_SQL, PaymentService.PAYER_WALLET, PaymentService.PAYER_WALLET);
        BigDecimal payerBal = (BigDecimal) ledger.get("payer_balance");
        BigDecimal supplierBal = (BigDecimal) ledger.get("supplier_balances");
        BigDecimal inEscrow = (BigDecimal) ledger.get("in_escrow");

        BigDecimal total = payerBal.add(supplierBal).add(inEscrow);
        boolean conserved = total.compareTo(new BigDecimal("1000000.00")) == 0;

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : List.of("SETTLED", "HELD", "RELEASED", "REVERTED", "BLOCKED")) {
            byStatus.put(s, 0);
        }
        BigDecimal protectedAmt = BigDecimal.ZERO;
        int protectedCnt = 0;

        // "Protected" = dinero que NO llego a un destino no autorizado: BLOCKED + REVERTED (igual que mocks.json).
        // Un pago HELD todavia no esta decidido: no cuenta hasta que se bloquea o se devuelve.
        for (Map<String, Object> row : jdbc.queryForList(METRICS_SQL)) {
            String status = String.valueOf(row.get("status"));
            int n = ((Number) row.get("n")).intValue();
            byStatus.merge(status, n, Integer::sum);
            if ("BLOCKED".equals(status) || "REVERTED".equals(status)) {
                protectedAmt = protectedAmt.add((BigDecimal) row.get("total"));
                protectedCnt += n;
            }
        }

        List<SupplierSummary> suppliers = parties.values().stream()
                .filter(p -> "SUPPLIER".equals(p.getRole()))
                .sorted(Comparator.comparing(PartyEntity::getId))
                .map(s -> {
                    String trusted = wallets.stream()
                            .filter(w -> w.getPartyId().equals(s.getId()) && "TRUSTED".equals(w.getStatus()))
                            .map(WalletEntity::getAddress)
                            .findFirst()
                            .orElse("");
                    return new SupplierSummary(s.getId(), s.getLegalName(), trusted, s.getKyb());
                })
                .toList();

        List<WalletView> walletViews = wallets.stream()
                .map(w -> viewMapper.toWalletView(w, parties.get(w.getPartyId())))
                .toList();

        List<PaymentView> paymentViews = allPayments.stream()
                .map(p -> {
                    WalletEntity pw = walletMap.get(p.getPayeeWallet());
                    PartyEntity owner = pw != null ? parties.get(pw.getPartyId()) : null;
                    return viewMapper.toPaymentView(p, parties.get(p.getSupplierId()), pw, owner);
                })
                .toList();

        return new AppState(
                AuditHasher.now(),
                new PayerSummary(
                        PaymentService.PAYER_ID,
                        payerParty != null ? payerParty.getLegalName() : "Northwind Manufacturing plc",
                        PaymentService.PAYER_WALLET,
                        ViewMapper.money(payerBal),
                        "GBPT"
                ),
                new LedgerSummary(
                        "GBPT",
                        ViewMapper.money(total),
                        ViewMapper.money(payerBal),
                        ViewMapper.money(inEscrow),
                        ViewMapper.money(supplierBal),
                        conserved
                ),
                new MetricsSummary(ViewMapper.money(protectedAmt), protectedCnt, byStatus),
                suppliers,
                walletViews,
                paymentViews,
                ruleService.list(),
                auditReadService.summary(12)
        );
    }
}
