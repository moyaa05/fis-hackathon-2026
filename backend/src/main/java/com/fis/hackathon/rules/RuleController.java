package com.fis.hackathon.rules;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET   /api/rules          -> reglas vigentes (también vienen dentro de GET /api/state)
 * PATCH /api/rules/{code}   -> cambio en vivo: {"enabled":false} o {"params":{"maxAttempts":2}}, + "changedBy"
 */
@RestController
public class RuleController {

    private final RuleService service;

    public RuleController(RuleService service) {
        this.service = service;
    }

    @GetMapping("/api/rules")
    public List<RuleService.RuleView> list() {
        return service.list();
    }

    @PatchMapping("/api/rules/{code}")
    public RuleService.RuleView patch(@PathVariable String code, @RequestBody RuleService.RulePatch patch) {
        return service.patch(code, patch);
    }

    @ExceptionHandler(RuleParams.InvalidParamsException.class)
    ResponseEntity<ProblemDetail> invalid(RuleParams.InvalidParamsException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMS", e.getMessage());
    }

    @ExceptionHandler(RuleService.UnknownRuleException.class)
    ResponseEntity<ProblemDetail> unknown(RuleService.UnknownRuleException e) {
        return problem(HttpStatus.NOT_FOUND, "UNKNOWN_RULE", e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return ResponseEntity.status(status).body(pd);
    }
}
