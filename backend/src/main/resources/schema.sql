-- =====================================================================
-- FIS Digital Assets Hackathon 2026 · Challenge 1 · Key-Rotation Escrow
-- schema.sql · SPEC v1 (CONGELADO)
-- Ubicación: backend/src/main/resources/schema.sql
-- application.yml: spring.jpa.hibernate.ddl-auto=none · spring.sql.init.mode=always
-- Se ejecuta en CADA arranque: drop & create => estado 100% determinista.
-- SOLO DDL puro (sin $$ ni PL/pgSQL: el ScriptUtils de Spring no los parte bien).
-- =====================================================================

DROP TABLE IF EXISTS audit_event, payment, rule, wallet, party CASCADE;

-- 1. PARTY · empresas (pagador, proveedores, terceros). KYB sintético en JSONB.
CREATE TABLE party (
    id          VARCHAR(40)   PRIMARY KEY,                 -- 'ACME', 'NORTHWIND' (legible y determinista)
    legal_name  TEXT          NOT NULL,
    role        VARCHAR(16)   NOT NULL CHECK (role IN ('PAYER','SUPPLIER','OTHER')),
    kyb         JSONB         NOT NULL DEFAULT '{}'::jsonb,  -- {"verified":true,"country":"GB","bank":"...","companyNo":"SIM-...","incorporatedDaysAgo":23}
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 2. WALLET · direcciones de depósito tokenizado (red permisionada entre bancos).
--    status relativo a su dueño: TRUSTED = wallet registrada vigente de la empresa.
CREATE TABLE wallet (
    address     VARCHAR(64)   PRIMARY KEY,                 -- 'tdgb:acme:op-01'
    party_id    VARCHAR(40)   NOT NULL REFERENCES party(id),
    bank        VARCHAR(60)   NOT NULL,                    -- banco emisor del depósito tokenizado (ficticio)
    public_key  TEXT          NOT NULL,                    -- Ed25519 · X.509 SubjectPublicKeyInfo DER · base64 ('MCowBQYDK2VwAyEA...')
    status      VARCHAR(16)   NOT NULL CHECK (status IN ('TRUSTED','UNVERIFIED','ROTATED','FLAGGED')),
    balance     NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    meta        JSONB         NOT NULL DEFAULT '{}'::jsonb,  -- {"label":"...","flagReason":"...","rotatedFrom":"...","rotatedTo":"..."}
    version     BIGINT        NOT NULL DEFAULT 0,          -- @Version (bloqueo optimista)
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- Invariante: como máximo UNA wallet TRUSTED por empresa.
-- OJO al rotar: primero old -> ROTATED, flush(), luego new -> TRUSTED (el índice se comprueba fila a fila).
CREATE UNIQUE INDEX ux_wallet_one_trusted_per_party ON wallet (party_id) WHERE status = 'TRUSTED';

-- 3. RULE · reglas programables editables en vivo (params en JSONB, versionadas).
CREATE TABLE rule (
    code         VARCHAR(40)  PRIMARY KEY,                 -- 'RISK_LIST' | 'VELOCITY_UNVERIFIED' | 'NEW_BENEFICIARY_WALLET'
    name         TEXT         NOT NULL,
    description  TEXT         NOT NULL,
    outcome      VARCHAR(8)   NOT NULL CHECK (outcome IN ('HOLD','BLOCK')),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    priority     INT          NOT NULL,
    params       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version      INT          NOT NULL DEFAULT 1,          -- +1 en cada PATCH; cada decisión cita la versión usada
    updated_by   VARCHAR(60)  NOT NULL DEFAULT 'system',
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 4. PAYMENT · pago + decisión explicable + escrow (challenge, intentos, resolución) en JSONB.
CREATE TABLE payment (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- 1,2,3... (ref 'PAY-0003' se formatea en el DTO)
    idempotency_key  VARCHAR(80)   NOT NULL UNIQUE,
    request_hash     VARCHAR(64)   NOT NULL,               -- SHA-256 del body: misma key + otro body => 409
    payer_wallet     VARCHAR(64)   NOT NULL REFERENCES wallet(address),
    supplier_id      VARCHAR(40)   NOT NULL REFERENCES party(id),
    payee_wallet     VARCHAR(64)   NOT NULL REFERENCES wallet(address),
    amount           NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency         VARCHAR(8)    NOT NULL DEFAULT 'GBPT',
    invoice_ref      VARCHAR(40)   NOT NULL,
    status           VARCHAR(16)   NOT NULL CHECK (status IN ('SETTLED','HELD','RELEASED','REVERTED','BLOCKED')),
    decision         JSONB         NOT NULL,               -- {"outcome","evaluatedAt","checks":[{code,name,result,ruleVersion,message,evidence}]}
    escrow           JSONB,                                -- solo si hubo HOLD (ver SPEC §3 y §5)
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_held_has_escrow CHECK (status <> 'HELD' OR escrow IS NOT NULL)
);
CREATE INDEX ix_payment_status   ON payment (status);
CREATE INDEX ix_payment_velocity ON payment (payer_wallet, supplier_id, created_at);

-- 5. AUDIT_EVENT · cadena SHA-256 append-only.
--    payload es TEXT (no JSONB): JSONB reordena claves y quita espacios => el hash dejaría de verificar.
CREATE TABLE audit_event (
    seq          BIGINT       PRIMARY KEY,                 -- max(seq)+1 bajo pg_advisory_xact_lock => sin huecos ni forks
    event_type   VARCHAR(40)  NOT NULL,
    entity_ref   VARCHAR(80)  NOT NULL,                    -- 'payment:3' | 'wallet:tdgb:acme:op-02' | 'rule:VELOCITY_UNVERIFIED' | 'system'
    actor        VARCHAR(60)  NOT NULL,                    -- 'engine' | 'ledger' | 'key-rotation' | 'scheduler' | 'demo' | 'compliance:<name>'
    payload      TEXT         NOT NULL,                    -- JSON canónico EXACTO que se hasheó
    occurred_at  TIMESTAMPTZ  NOT NULL,                    -- truncado a milisegundos ANTES de hashear
    prev_hash    VARCHAR(64)  NOT NULL,                    -- génesis: 64 ceros
    hash         VARCHAR(64)  NOT NULL UNIQUE
);
CREATE INDEX ix_audit_entity ON audit_event (entity_ref);
