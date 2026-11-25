package com.fis.hackathon.ledger.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "wallet")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WalletEntity {

    @Id
    @Column(length = 64)
    private String address;

    @Column(name = "party_id", nullable = false, length = 40)
    private String partyId;

    @Column(nullable = false, length = 60)
    private String bank;

    @Column(name = "public_key", nullable = false, columnDefinition = "text")
    private String publicKey;

    @Column(nullable = false, length = 16)
    private String status; // TRUSTED | UNVERIFIED | ROTATED | FLAGGED

    @Column(nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> meta = new HashMap<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}