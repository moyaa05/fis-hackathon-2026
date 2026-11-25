package com.fis.hackathon.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Fila de la tabla audit_event (schema.sql). Solo se inserta; nunca se actualiza desde la aplicación. */
@Entity
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    @Column(name = "seq")
    private Long seq;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "entity_ref", nullable = false)
    private String entityRef;

    @Column(name = "actor", nullable = false)
    private String actor;

    /** TEXT, no JSONB: es el String EXACTO que se hasheó. */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "prev_hash", nullable = false)
    private String prevHash;

    @Column(name = "hash", nullable = false, unique = true)
    private String hash;

    protected AuditEventEntity() {}

    public AuditEventEntity(Long seq, String eventType, String entityRef, String actor, String payload,
                            Instant occurredAt, String prevHash, String hash) {
        this.seq = seq;
        this.eventType = eventType;
        this.entityRef = entityRef;
        this.actor = actor;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public Long getSeq() { return seq; }
    public String getEventType() { return eventType; }
    public String getEntityRef() { return entityRef; }
    public String getActor() { return actor; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getPrevHash() { return prevHash; }
    public String getHash() { return hash; }

    ChainVerifier.Row toRow() {
        return new ChainVerifier.Row(seq, eventType, entityRef, actor, occurredAt, payload, prevHash, hash);
    }
}
