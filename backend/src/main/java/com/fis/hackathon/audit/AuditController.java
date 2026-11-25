package com.fis.hackathon.audit;

import com.fis.hackathon.shared.ChainReport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GET  /api/audit?limit=200   -> log completo (SPEC §5, endpoint 7)
 * GET  /api/audit/verify      -> verificación de la cadena (endpoint 8)
 * POST /api/demo/tamper       -> SOLO DEMO: edita un evento directamente en la BD, sin recalcular el hash (endpoint 10)
 */
@RestController
public class AuditController {

    private static final Pattern AMOUNT = Pattern.compile("\"amount\":\"(\\d+\\.\\d{2})\"");

    private final AuditReadService readService;
    private final AuditLog auditLog;
    private final JdbcTemplate jdbc;

    public AuditController(AuditReadService readService, AuditLog auditLog, JdbcTemplate jdbc) {
        this.readService = readService;
        this.auditLog = auditLog;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/audit")
    public AuditReadService.AuditList list(@RequestParam(defaultValue = "200") int limit) {
        return readService.list(limit);
    }

    @GetMapping("/api/audit/verify")
    public ChainReport verify() {
        return auditLog.verify();
    }

    /** Simula a alguien con acceso directo a la BD que "maquilla" un importe. Body opcional: {"seq": 7}. */
    @PostMapping("/api/demo/tamper")
    public ResponseEntity<?> tamper(@RequestBody(required = false) Map<String, Object> body) {
        Long seq = body != null && body.get("seq") instanceof Number n ? n.longValue() : null;
        if (seq == null) {
            List<Long> last = jdbc.queryForList(
                    "SELECT seq FROM audit_event WHERE event_type LIKE 'FUNDS_%' ORDER BY seq DESC LIMIT 1", Long.class);
            if (last.isEmpty()) return problem(HttpStatus.NOT_FOUND, "NOTHING_TO_TAMPER", "No FUNDS_* event in the audit log yet.");
            seq = last.get(0);
        }
        List<String> payloads = jdbc.queryForList("SELECT payload FROM audit_event WHERE seq = ?", String.class, seq);
        if (payloads.isEmpty()) return problem(HttpStatus.NOT_FOUND, "UNKNOWN_EVENT", "Audit event " + seq + " does not exist.");
        String payload = payloads.get(0);
        Matcher m = AMOUNT.matcher(payload);
        if (!m.find()) return problem(HttpStatus.CONFLICT, "NO_AMOUNT", "Audit event " + seq + " has no amount to tamper with.");
        String before = m.group(1);
        String after = new BigDecimal(before).divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP).toPlainString();
        String tampered = payload.replace("\"amount\":\"" + before + "\"", "\"amount\":\"" + after + "\"");
        jdbc.update("UPDATE audit_event SET payload = ? WHERE seq = ?", tampered, seq);
        return ResponseEntity.ok(Map.of("tamperedSeq", seq, "field", "amount", "before", before, "after", after,
                "note", "Direct DB write that bypasses the application. Hash NOT recomputed."));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return ResponseEntity.status(status).body(pd);
    }
}
