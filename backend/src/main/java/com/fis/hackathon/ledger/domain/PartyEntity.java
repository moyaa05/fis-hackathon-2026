package com.fis.hackathon.ledger.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "party")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartyEntity {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "legal_name", nullable = false, columnDefinition = "text")
    private String legalName;

    @Column(nullable = false, length = 16)
    private String role; // PAYER | SUPPLIER | OTHER

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> kyb = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}