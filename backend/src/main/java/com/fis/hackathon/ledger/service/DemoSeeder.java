package com.fis.hackathon.ledger.service;

import com.fis.hackathon.api.dto.ApiDtos.CreatePaymentRequest;
import com.fis.hackathon.api.dto.ApiDtos.ResetResult;
import com.fis.hackathon.audit.AuditHasher;
import com.fis.hackathon.audit.AuditLog;
import com.fis.hackathon.audit.AuditReadService;
import com.fis.hackathon.crypto.SupplierSimulator;
import com.fis.hackathon.ledger.LedgerLock;
import com.fis.hackathon.ledger.domain.PartyEntity;
import com.fis.hackathon.ledger.domain.WalletEntity;
import com.fis.hackathon.ledger.repository.PartyRepository;
import com.fis.hackathon.ledger.repository.PaymentRepository;
import com.fis.hackathon.ledger.repository.WalletRepository;
import com.fis.hackathon.payments.service.PaymentService;
import com.fis.hackathon.rules.RuleService;
import jakarta.persistence.EntityManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class DemoSeeder implements ApplicationRunner {

    private final LedgerLock ledgerLock;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final PartyRepository partyRepo;
    private final WalletRepository walletRepo;
    private final PaymentRepository paymentRepo;
    private final RuleService ruleService;
    private final SupplierSimulator supplierSimulator;
    private final AuditLog auditLog;
    private final AuditReadService auditReadService;
    private final PaymentService paymentService;

    public DemoSeeder(
            LedgerLock ledgerLock,
            JdbcTemplate jdbc,
            EntityManager entityManager,
            PartyRepository partyRepo,
            WalletRepository walletRepo,
            PaymentRepository paymentRepo,
            RuleService ruleService,
            SupplierSimulator supplierSimulator,
            AuditLog auditLog,
            AuditReadService auditReadService,
            PaymentService paymentService
    ) {
        this.ledgerLock = ledgerLock;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.partyRepo = partyRepo;
        this.walletRepo = walletRepo;
        this.paymentRepo = paymentRepo;
        this.ruleService = ruleService;
        this.supplierSimulator = supplierSimulator;
        this.auditLog = auditLog;
        this.auditReadService = auditReadService;
        this.paymentService = paymentService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        reset();
    }

    @Transactional
    public ResetResult reset() {
        ledgerLock.acquire();

        jdbc.execute("TRUNCATE audit_event, payment, rule, wallet, party RESTART IDENTITY CASCADE");
        entityManager.clear();

        ruleService.seedDefaults();

        Instant now = AuditHasher.now();
        partyRepo.saveAll(List.of(
                PartyEntity.builder().id("NORTHWIND").legalName("Northwind Manufacturing plc").role("PAYER")
                        .kyb(Map.of("verified", true, "country", "GB", "bank", "Thames Bank (sim)", "companyNo", "SIM-08412901")).createdAt(now).build(),
                PartyEntity.builder().id("ACME").legalName("Acme Components Ltd").role("SUPPLIER")
                        .kyb(Map.of("verified", true, "country", "GB", "bank", "Albion Bank (sim)", "companyNo", "SIM-05194820")).createdAt(now).build(),
                PartyEntity.builder().id("BRIGHTWATER").legalName("Brightwater Logistics Ltd").role("SUPPLIER")
                        .kyb(Map.of("verified", true, "country", "GB", "bank", "Thames Bank (sim)", "companyNo", "SIM-06923114")).createdAt(now).build(),
                PartyEntity.builder().id("KESTREL").legalName("Kestrel Packaging Ltd").role("SUPPLIER")
                        .kyb(Map.of("verified", true, "country", "GB", "bank", "Albion Bank (sim)", "companyNo", "SIM-07310492")).createdAt(now).build(),
                PartyEntity.builder().id("ACME_TRADING").legalName("Acme Trading Ltd").role("OTHER")
                        .kyb(Map.of("verified", true, "country", "GB", "bank", "Severn Bank (sim)", "companyNo", "SIM-15980211", "incorporatedDaysAgo", 23)).createdAt(now).build(),
                PartyEntity.builder().id("ORION").legalName("Orion Global Trade Ltd").role("OTHER")
                        .kyb(Map.of("verified", false, "country", "GB", "bank", "Severn Bank (sim)", "companyNo", "SIM-09911004")).createdAt(now).build()
        ));

        walletRepo.saveAll(List.of(
                wallet("tdgb:northwind:treasury", "NORTHWIND", "Thames Bank (sim)", "TRUSTED", new BigDecimal("1000000.00"), Map.of("label", "Primary Treasury")),
                wallet("tdgb:acme:op-01", "ACME", "Albion Bank (sim)", "TRUSTED", BigDecimal.ZERO, Map.of("label", "Registered Operating Account")),
                wallet("tdgb:acme:op-02", "ACME", "Mercia Bank (sim)", "UNVERIFIED", BigDecimal.ZERO, Map.of("label", "New Mercia Bank Treasury")),
                wallet("tdgb:acmetrading:7731", "ACME_TRADING", "Severn Bank (sim)", "UNVERIFIED", BigDecimal.ZERO, Map.of("label", "Acme Trading Settlement")),
                wallet("tdgb:brightwater:main", "BRIGHTWATER", "Thames Bank (sim)", "TRUSTED", BigDecimal.ZERO, Map.of("label", "Brightwater Main")),
                wallet("tdgb:kestrel:main", "KESTREL", "Albion Bank (sim)", "TRUSTED", BigDecimal.ZERO, Map.of("label", "Kestrel Main")),
                wallet("tdgb:kestrel:new-19", "KESTREL", "Severn Bank (sim)", "UNVERIFIED", BigDecimal.ZERO, Map.of("label", "Kestrel New Account")),
                wallet("tdgb:orion:01", "ORION", "Severn Bank (sim)", "FLAGGED", BigDecimal.ZERO, Map.of("label", "Orion Account", "flagReason", "UK sanctions list (OFSI, simulated)"))
        ));
        walletRepo.flush();

        Map<String, Object> resetPayload = new TreeMap<>();
        resetPayload.put("rules", 3);
        resetPayload.put("seedVersion", "v1");
        resetPayload.put("totalSupply", "1000000.00");
        resetPayload.put("wallets", 8);
        auditLog.append("DEMO_RESET", "system", "demo", resetPayload);

        // PAY-0001 sembrado a través de PaymentService -> HELD (auditLength = 3)
        paymentService.create(
                new CreatePaymentRequest("KESTREL", "tdgb:kestrel:new-19", "15000.00", "INV-KP-0877"),
                "seed-pay-0001"
        );

        var summary = auditReadService.summary(1);
        return new ResetResult(true, AuditHasher.now(), summary.headHash(), (int) paymentRepo.count(), summary.length());
    }

    private WalletEntity wallet(String addr, String partyId, String bank, String status, BigDecimal balance, Map<String, Object> meta) {
        return WalletEntity.builder()
                .address(addr)
                .partyId(partyId)
                .bank(bank)
                .publicKey(supplierSimulator.publicKeyOf(addr))
                .status(status)
                .balance(balance)
                .meta(meta)
                .updatedAt(AuditHasher.now())
                .build();
    }
}
