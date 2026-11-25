package com.fis.hackathon.rules;

import com.fis.hackathon.audit.AuditHasher;
import com.fis.hackathon.audit.AuditLog;
import com.fis.hackathon.ledger.LedgerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gobernanza de reglas: listar, sembrar y cambiar en vivo.
 * Cada cambio sube la versión y deja un evento RULE_UPDATED con el antes y el después en el audit.
 * Antonio: en GET /api/state usa list(); en el reset llama a seedDefaults() DESPUÉS del TRUNCATE.
 */
@Service
public class RuleService {

    public record RuleView(String code, String name, String description, String outcome, boolean enabled,
                           int priority, Map<String, Object> params, int version, String updatedBy, String updatedAt) {
        static RuleView of(RuleDef d) {
            return new RuleView(d.code(), d.name(), d.description(), d.outcome(), d.enabled(), d.priority(),
                    d.params(), d.version(), d.updatedBy(), AuditHasher.MILLIS.format(d.updatedAt()));
        }
    }

    public record RulePatch(Boolean enabled, Map<String, Object> params, String changedBy) {}

    public static final class UnknownRuleException extends RuntimeException {
        public UnknownRuleException(String code) { super("Rule " + code + " does not exist."); }
    }

    private final RuleRepository repository;
    private final AuditLog auditLog;
    private final LedgerLock ledgerLock;

    public RuleService(RuleRepository repository, AuditLog auditLog, LedgerLock ledgerLock) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.ledgerLock = ledgerLock;
    }

    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return repository.findAllByOrderByPriorityAsc().stream().map(e -> RuleView.of(e.toDef())).toList();
    }

    /** Inserta las 3 reglas por defecto (versión 1). Llamar dentro de la transacción del reset. */
    @Transactional
    public void seedDefaults() {
        Instant now = AuditHasher.now();
        repository.saveAll(RuleCatalog.defaults(now).stream().map(RuleEntity::from).toList());
    }

    @Transactional
    public RuleView patch(String code, RulePatch patch) {
        ledgerLock.acquire();
        RuleEntity rule = repository.findById(code).orElseThrow(() -> new UnknownRuleException(code));
        Map<String, Object> before = snapshot(rule);

        Map<String, Object> merged = null;
        if (patch.params() != null) {
            merged = new LinkedHashMap<>(RuleParams.normalize(code, rule.getParams()));   // normaliza lo existente
            merged.putAll(RuleParams.normalize(code, patch.params()));                    // y lo nuevo (valida)
        }
        String actor = patch.changedBy() == null || patch.changedBy().isBlank() ? "compliance:unknown" : patch.changedBy().trim();
        rule.applyChange(patch.enabled(), merged, actor, AuditHasher.now());

        auditLog.append("RULE_UPDATED", "rule:" + code, actor,
                Map.of("rule", code, "before", before, "after", snapshot(rule)));
        return RuleView.of(rule.toDef());
    }

    private static Map<String, Object> snapshot(RuleEntity r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", r.isEnabled());
        s.put("params", RuleParams.normalize(r.getCode(), r.getParams() == null ? Map.of() : r.getParams()));
        s.put("version", r.getVersion());
        return s;
    }
}
