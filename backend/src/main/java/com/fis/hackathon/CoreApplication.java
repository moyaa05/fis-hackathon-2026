package com.fis.hackathon;

import com.fis.hackathon.audit.AuditHasher;
import com.fis.hackathon.audit.AuditLog;
import com.fis.hackathon.shared.ChainReport;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@SpringBootApplication
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@OpenAPIDefinition(
    info = @Info(
        title = "FIS Programmable Engine API",
        version = "1.0.0",
        description = "Core Ledger & Policy Control Plane"
    ),
    servers = @Server(url = "/", description = "Gateway Active Origin")
)
public class CoreApplication {

    private final AuditLog auditLog;

    public CoreApplication(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }

    /** SPEC §5 (endpoint 11): estado del servicio + cabeza REAL de la cadena de audit, recalculada en cada llamada. */
    @GetMapping("/health")
    public Map<String, Object> checkHealth() {
        ChainReport chain = auditLog.verify();
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "OPERATIONAL");
        health.put("node", "FIS-Core-Engine-v1");
        health.put("thread", Thread.currentThread().toString());
        health.put("headHash", chain.headHash());
        health.put("chainValid", chain.valid());
        health.put("auditLength", chain.length());
        health.put("ledgerIntegrityHash", chain.headHash());   // alias: la pantalla esqueleto antigua lee este campo
        health.put("timestamp", AuditHasher.MILLIS.format(AuditHasher.now()));
        return health;
    }
}
