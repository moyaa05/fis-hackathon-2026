package com.fis.hackathon;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

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

    private final ReentrantLock transactionLock = new ReentrantLock(true);

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }

    @GetMapping("/health")
    public Map<String, Object> checkHealth() {
        transactionLock.lock();
        try {
            String timestamp = Instant.now().toString();
            String genesisHash = sha256("FIS-GENESIS-BLOCK-" + timestamp);
            return Map.of(
                "status", "OPERATIONAL",
                "node", "FIS-Core-Engine-v1",
                "thread", Thread.currentThread().toString(),
                "ledgerIntegrityHash", genesisHash,
                "timestamp", timestamp
            );
        } finally {
            transactionLock.unlock();
        }
    }

    public static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error generating cryptographic hash", e);
        }
    }
}
