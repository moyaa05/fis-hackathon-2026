package com.fis.hackathon.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fila de la tabla rule (schema.sql). params es JSONB. */
@Entity
@Table(name = "rule")
public class RuleEntity {

    @Id
    @Column(name = "code")
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "priority", nullable = false)
    private int priority;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> params = new LinkedHashMap<>();

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RuleEntity() {}

    public static RuleEntity from(RuleDef d) {
        RuleEntity e = new RuleEntity();
        e.code = d.code();
        e.name = d.name();
        e.description = d.description();
        e.outcome = d.outcome();
        e.enabled = d.enabled();
        e.priority = d.priority();
        e.params = new LinkedHashMap<>(d.params());
        e.version = d.version();
        e.updatedBy = d.updatedBy();
        e.updatedAt = d.updatedAt();
        return e;
    }

    public RuleDef toDef() {
        return new RuleDef(code, name, description, outcome, enabled, priority,
                params == null ? Map.of() : new LinkedHashMap<>(params), version, updatedBy, updatedAt);
    }

    public String getCode() { return code; }
    public boolean isEnabled() { return enabled; }
    public Map<String, Object> getParams() { return params; }
    public int getVersion() { return version; }

    /** Cambio gobernado: siempre sube la versión y registra quién y cuándo. Reasigna el Map (no lo muta). */
    void applyChange(Boolean newEnabled, Map<String, Object> newParams, String changedBy, Instant when) {
        if (newEnabled != null) this.enabled = newEnabled;
        if (newParams != null) this.params = new LinkedHashMap<>(newParams);
        this.version = this.version + 1;
        this.updatedBy = changedBy;
        this.updatedAt = when;
    }
}
