# SPEC v1 · Challenge 1 · Key-Rotation Escrow — contrato técnico CONGELADO

> **Estado:** congelado miércoles 25-nov. Cambiar cualquier cosa de este documento exige el OK de Antonio + Alejandro + Tomás en un checkpoint.
> **Alcance (v1.1, 25-nov 19:20):** solo Reino Unido: pagos B2B en depósitos tokenizados en libras entre bancos del Reino Unido. Pagos internacionales, SWIFT y otras jurisdicciones quedan como líneas futuras, fuera de la demo.
> **Archivos hermanos:** `schema.sql` (DDL probado en PostgreSQL 16) · `mocks.json` (respuestas reales de cada endpoint, para Tomás) · `test-vectors.json` (vectores para JUnit) · `demo-keys.json` (claves Ed25519 sintéticas).
> Todos los hashes y firmas de `mocks.json` y `test-vectors.json` son reales: se verificaron con Python `cryptography` y con el CLI de OpenSSL.
>
> **Cambios v1.2 (25-nov 23:30, tras la auditoría; ya en `main`):**
> 1. `WalletView` añade `publicKey` (clave **pública** Ed25519, SPKI base64) para que la UI enseñe la huella de cada clave.
> 2. `rotation-authorization` puede devolver además `409 ESCROW_EXPIRED` (firma recibida después de `expiresAt`) y `409 DESTINATION_FLAGGED` (el destino entró en la lista de riesgo mientras el pago estaba retenido; los fondos vuelven al vencer).
> 3. `metrics.protectedAmount/Count` = pagos `BLOCKED` + `REVERTED`. Un `HELD` todavía no cuenta.
> 4. Todos los errores, también los del framework (JSON mal formado, 404, 405, 415…), salen como problem+json con `code`. Nuevos códigos genéricos: `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR`.
> 5. `supplierId` tiene que ser una empresa con rol `SUPPLIER`; si no, `404 UNKNOWN_SUPPLIER`.
> 6. `/api/health` devuelve `headHash`, `chainValid` y `auditLength` (cabeza real de la cadena).
> 7. `POST /api/demo/tamper` **sin body** es la forma recomendada: elige el último evento `FUNDS_*`, así funciona en cualquier punto de la demo.
> 8. UI: el cuarto pago de la demo es Kestrel → `tdgb:orion:01` (`BLOCKED` por `RISK_LIST`). Repetir el pago "URGENT" de Acme tras el bloqueo también da `BLOCKED`.
> 9. `scripts/matrix.py` automatiza T1–T17 y T19 (parte de T8 con un reset: en orden literal, T3+T5+T6+T8 superan el límite de velocidad de Acme).

---

## 0. Convenciones (aplican a todo)

- **Base URL:** rutas relativas `/api/...` siempre a través del gateway `http://localhost:18080`. Nadie usa `:5173` ni `:8081` directamente: el proxy de Vite apunta a `localhost:8081`, que *dentro* del contenedor no es el backend.
- **JSON** camelCase. **Dinero = string** con 2 decimales (`"80000.00"`), nunca `number`. Backend: `BigDecimal` + `NUMERIC(18,2)`. Moneda única: `GBPT` (depósito tokenizado en GBP).
- **Fechas en vistas:** ISO-8601 UTC; el frontend las parsea con `new Date()`. El formato estricto solo aplica a lo que se firma (§3) o se hashea (§4).
- **Errores:** `application/problem+json` → `{type, title, status, code, detail}` (Spring `ProblemDetail` + propiedad `code`). El frontend muestra `detail`.
- **Un BLOCK no es un error HTTP:** es un resultado de negocio → `201/200` con `status: "BLOCKED"`.
- **Idioma:** textos de UI y mensajes de la API en **inglés** (jurado en Londres).
- **Formato Reino Unido en la UI:** `GBPT` no es un código ISO 4217 y `Intl.NumberFormat(..., {currency: 'GBPT'})` lanza `RangeError`. Formatear con `new Intl.NumberFormat('en-GB', {style: 'currency', currency: 'GBP'})` y rotular como *tokenised £*. Horas con `timeZone: 'Europe/London'` (en noviembre, Londres = UTC).
- `/api/demo/**` y `/api/sim/**` son controles de demo y simulación de actores externos. No existen en producción; decirlo así si preguntan.

```text
PaymentStatus   SETTLED | HELD | RELEASED | REVERTED | BLOCKED
Outcome         APPROVE | HOLD | BLOCK
CheckResult     PASS | HOLD | BLOCK | SKIPPED          (SKIPPED = regla desactivada)
WalletStatus    TRUSTED | UNVERIFIED | ROTATED | FLAGGED
RotationResult  VALID | SELF_AUTHORISATION | INVALID | MALFORMED
Resolution      RELEASED_KEY_ROTATION | RELEASED_ONE_OFF | BLOCKED_SELF_AUTHORISATION | BLOCKED_MAX_ATTEMPTS | RETURNED_EXPIRED
SimActor        LEGIT_SUPPLIER | ATTACKER | TAMPERED
```

---

## 1. Modelo de datos → `schema.sql` (5 tablas)

| Tabla | Qué guarda | JSONB |
|---|---|---|
| `party` | Empresas: pagador, proveedores, terceros | `kyb` |
| `wallet` | Direcciones de depósito tokenizado + clave pública Ed25519 + saldo + estado | `meta` (label, flagReason, rotatedFrom/To) |
| `rule` | Reglas programables versionadas | `params` |
| `payment` | Pago + decisión explicable + escrow (challenge, intentos, resolución) | `decision`, `escrow` |
| `audit_event` | Cadena SHA-256 append-only | — (`payload` es TEXT a propósito, ver §4) |

Las claves **privadas** no están en la BD: viven en `demo-keys.json` y solo las lee el simulador (§3.5). El modelo de datos del PDF muestra estas 5 tablas, sin claves privadas.

**Invariantes** (la matriz de pruebas §9 las comprueba):

- **I1 · Conservación:** `Σ wallet.balance + Σ amount(HELD) = 1,000,000.00` siempre.
- **I2 · Una sola wallet TRUSTED por empresa** (índice único parcial). Al rotar: old → `ROTATED`, `flush()`, new → `TRUSTED`. Hibernate **no** garantiza el orden de los UPDATE dentro de un flush; con el orden inverso PostgreSQL lanza `unique_violation` (probado).
- **I3 · HELD ⇒ escrow no nulo** (CHECK en BD).
- **I4 · Toda resolución parte de HELD** y se comprueba dentro del lock → `authorize` y `expire` nunca resuelven el mismo pago dos veces.
- **I5 · Todo cambio de dinero, wallet o regla escribe su evento de audit en la misma transacción.**

```text
POST /payments ─► decisión ─ APPROVE ─► SETTLED
                           ├ BLOCK ───► BLOCKED            (no se mueve dinero)
                           └ HOLD ────► HELD ─ VALID ─────────────────► RELEASED  (+ rotación si es la misma empresa)
                                             ├ SELF_AUTHORISATION ────► BLOCKED   (+ devolución + wallet FLAGGED)
                                             ├ INVALID × maxInvalid ──► BLOCKED   (+ devolución)
                                             └ expiresAt / expire ────► REVERTED  (+ devolución)
```

**Notas JPA:**

- **Mapear JSONB:** `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") Map<String,Object>`, o un record.
- **Modificar JSONB:** reasignad el objeto (`p.setEscrow(nuevo)`); no lo mutéis in-place.
- **El challenge incluye el `id` del pago:** `save()` + `flush()` para obtener el IDENTITY, construir el challenge y luego `setEscrow(...)`, todo en la misma transacción.
- **Reset:** `TRUNCATE audit_event, payment, rule, wallet, party RESTART IDENTITY CASCADE` + seed. Los ids vuelven a empezar en 1, así que la demo es idéntica cada vez.

---

## 2. Motor de decisión (reglas v1)

| Prio | `code` | Outcome | `params` por defecto | Se dispara si… |
|---|---|---|---|---|
| 1 | `RISK_LIST` | BLOCK | `{}` | `payee.status = FLAGGED` |
| 2 | `VELOCITY_UNVERIFIED` | BLOCK | `{"maxAttempts":3,"windowMinutes":30}` | El pago va a una wallet no-TRUSTED y `n > maxAttempts`, con `n` = intentos previos a wallets no verificadas para ese proveedor en la ventana + 1 |
| 3 | `NEW_BENEFICIARY_WALLET` | HOLD | `{"holdMinutes":120,"minAmount":"0.00","maxInvalidAttempts":3}` | `payee ≠` wallet TRUSTED del proveedor y `amount ≥ minAmount` |

- **Se evalúan todas las reglas** en orden de prioridad. Cada una produce un `Check`; una regla desactivada produce `SKIPPED`. La explicación muestra lo que pasó y también lo que se comprobó.
- **Resultado:** `BLOCK` si hay algún BLOCK; si no, `HOLD` si hay algún HOLD; si no, `APPROVE`.
- **Versiones:** cada `Check` lleva `ruleVersion`, así que una decisión cita la versión exacta de la regla que la tomó (gobernanza).
- **El motor no escribe en BD.** Lee las reglas y cuenta intentos con:

```sql
SELECT count(*) FROM payment
WHERE payer_wallet = :payer AND supplier_id = :supplier
  AND created_at > now() - make_interval(mins => :windowMinutes)
  AND decision->'checks' @> '[{"code":"NEW_BENEFICIARY_WALLET","result":"HOLD"}]';
```

- **Mensajes:** plantillas exactas en `mocks.json` (`state.body.payments[*].decision.checks[*].message`). Ejemplo HOLD: *"Destination wallet tdgb:acmetrading:7731 (owner: Acme Trading Ltd, KYB verified) is not Acme Components Ltd's registered wallet tdgb:acme:op-01. Funds locked in escrow until Acme Components Ltd authorises the change with its registered key. Automatic return at 23:02 UTC."*
- **Validación previa** (no son reglas, devuelven error HTTP):
  - Proveedor o wallet inexistente → `404 UNKNOWN_SUPPLIER` / `404 UNKNOWN_WALLET`.
  - `amount ≤ 0` o más de 2 decimales → `422 INVALID_AMOUNT`.
  - Saldo insuficiente → `422 INSUFFICIENT_FUNDS`.

---

## 3. Key rotation (Ed25519 nativo de JDK 21)

### 3.1 Challenge: bytes exactos que se firman

```text
FIS-KEYROT-v1
payment=3
supplier=ACME
trustedWallet=tdgb:acme:op-01
requestedWallet=tdgb:acmetrading:7731
amount=80000.00
currency=GBPT
nonce=NtDhc-ngjY_IWbQ-3sgf8Q
expiresAt=2026-11-25T23:02:41Z
```

- **Formato:** líneas unidas con `\n` (LF), sin salto final, UTF-8.
- **`nonce`:** 16 bytes de `SecureRandom` en base64url sin padding.
- **`expiresAt`:** `createdAt + holdMinutes`, truncado a segundos, patrón `yyyy-MM-dd'T'HH:mm:ss'Z'`.
- **Por qué cada campo:**
  - `FIS-KEYROT-v1` separa dominios: esta firma no sirve para nada más.
  - `payment` + `nonce` impiden reutilizar la firma en otro pago (replay).
  - `requestedWallet` + `amount` impiden cambiar el destino o el importe (sustitución).
  - `expiresAt` acota la validez en el tiempo.

### 3.2 Codificaciones
- **Clave pública:** SPKI DER en base64 (`X509EncodedKeySpec`).
- **Clave privada:** PKCS#8 DER en base64 (`PKCS8EncodedKeySpec`).
- **Clases JDK:** `KeyFactory.getInstance("Ed25519")` y `Signature.getInstance("Ed25519")`.
- **Firma:** 64 bytes en base64 estándar con padding (88 caracteres).

### 3.3 Clasificación: `KeyRotationService.verify` nunca lanza excepción

```text
bytes = base64.decode(signature)   → error, o len ≠ 64            ⇒ MALFORMED   (400, sin cambio de estado)
verify(escrow.trustedPublicKey, challenge, bytes)                  ⇒ VALID
verify(publicKey(escrow.requestedWallet), challenge, bytes)        ⇒ SELF_AUTHORISATION
en otro caso                                                       ⇒ INVALID
```

La clave de verificación es el **snapshot** `escrow.trustedPublicKey` guardado al retener los fondos, no la clave que haya en ese momento en `wallet`.

### 3.4 Efectos

| Resultado | Efecto | Eventos de audit |
|---|---|---|
| VALID y `requested.party = supplier` | RELEASED; fondos al payee; old → ROTATED, new → TRUSTED | `ROTATION_SIGNATURE_VERIFIED`, `WALLET_ROTATED`, `FUNDS_RELEASED` |
| VALID y `requested.party ≠ supplier` | RELEASED sin rotar (pago puntual autorizado) → `RELEASED_ONE_OFF` | `ROTATION_SIGNATURE_VERIFIED`, `FUNDS_RELEASED` |
| SELF_AUTHORISATION | BLOCKED; devolución; requested → FLAGGED | `ROTATION_SIGNATURE_REJECTED`, `FUNDS_RETURNED`, `WALLET_FLAGGED` |
| INVALID (intento inválido n < max) | Sigue HELD; se registra el intento | `ROTATION_SIGNATURE_REJECTED` |
| INVALID (n = max) | BLOCKED; devolución → `BLOCKED_MAX_ATTEMPTS` | `ROTATION_SIGNATURE_REJECTED`, `FUNDS_RETURNED` |
| MALFORMED | Nada (`400`) | — |
| Vence `expiresAt` / `expire` | REVERTED; devolución → `RETURNED_EXPIRED` | `ESCROW_EXPIRED`, `FUNDS_RETURNED` |

**Por qué INVALID no bloquea al primer intento:** el endpoint es público. Si una firma basura bloqueara el pago, cualquiera podría tumbar pagos legítimos (ataque de denegación). SELF_AUTHORISATION sí bloquea al instante porque es la huella exacta del atacante BEC: una firma válida, pero hecha con la clave de la wallet que pide el dinero.

### 3.5 Simulador `/api/sim/sign`
Es la única pieza que lee claves privadas, desde `demo-keys.json` en el classpath.
- **LEGIT_SUPPLIER:** firma el challenge con la clave de `trustedWallet`.
- **ATTACKER:** firma el mismo challenge con la clave de `requestedWallet`.
- **TAMPERED:** firma con la clave legítima un challenge con `requestedWallet` cambiado.

### 3.6 Vectores
Con los datos de `test-vectors.json → ed25519.cases`, vuestro `verify` debe devolver exactamente: `VALID`, `SELF_AUTHORISATION`, `INVALID`, `MALFORMED`, `MALFORMED`.

---

## 4. Audit chain (SHA-256)

### 4.1 Fórmula

```text
hash = hex( SHA-256( UTF-8( seq|eventType|entityRef|actor|occurredAt|payload|prevHash ) ) )     // hex en minúsculas
génesis: prevHash = "0000…0000" (64 ceros)
```

- **`occurredAt`:** `Instant.now().truncatedTo(MILLIS)` formateado con `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(UTC)`. **No** uséis `Instant.toString()`: omite la fracción cuando los milisegundos son `.000`, y la verificación fallaría 1 de cada 1.000 eventos.
- **Comprobado en PostgreSQL 16:**
  - `'{"b":1, "a":2}'::jsonb::text` devuelve `{"a": 2, "b": 1}` (reordena claves y cambia espacios). Por eso `payload` es TEXT.
  - `'…05.123456789Z'::timestamptz` devuelve `.123457`: redondea a microsegundos. Por eso se truncan los milisegundos **antes** de hashear.

### 4.2 JSON canónico del payload
- Claves ordenadas en todos los niveles: `ORDER_MAP_ENTRIES_BY_KEYS` + `SORT_PROPERTIES_ALPHABETICALLY`. Construid los payloads como `TreeMap`.
- Sin espacios.
- Dinero como string.
- Las claves con valor `null` se **omiten** (`JsonInclude.Include.NON_NULL`).
- Sin caracteres no ASCII (nada de "£").
- Se guarda **el mismo String** que se hasheó.

### 4.3 Concurrencia (lo que evita forks de la cadena)
- **Primera sentencia de toda transacción que modifique datos:** `SELECT pg_advisory_xact_lock(4242)`. Se libera en el commit, así que es correcto también con N réplicas. `AuditLog.append()` también lo pide; es reentrante en la misma transacción (probado).
- **`seq`** = `COALESCE(MAX(seq),0)+1`; **`prevHash`** = hash del último evento.
- **No uséis `ReentrantLock` alrededor de un método `@Transactional`.** El lock se libera al salir del método, pero el commit lo hace el proxy después. Otro hilo lee el head antiguo y aparecen dos eventos con el mismo `prev_hash`. Si queréis conservarlo, el orden es lock → `TransactionTemplate.execute(...)` → unlock.

### 4.4 Verificación
Se recorre la cadena por `seq` y se devuelve el **primer** fallo:
- `SEQ_GAP`: falta un número de secuencia.
- `BROKEN_LINK`: `prev_hash` no coincide con el hash anterior.
- `HASH_MISMATCH`: al recalcular, el hash no coincide con el guardado.

`GET /api/state` incluye `audit.chainValid`, recalculado en cada petición (menos de 100 eventos, coste despreciable). Así, si se manipula la BD desde psql, la cabecera de la UI se pone roja sola en un segundo.

### 4.5 Catálogo de eventos (congelado)

| eventType | entityRef | actor | payload (claves) |
|---|---|---|---|
| `DEMO_RESET` | `system` | `demo` | `seedVersion, totalSupply, wallets, rules` |
| `PAYMENT_DECIDED` | `payment:{id}` | `engine` | `amount, currency, initiatedBy, invoiceRef, outcome, payeeWallet, supplierId, checks[{code,result,ruleVersion}]` |
| `FUNDS_SETTLED` | `payment:{id}` | `ledger` | `amount, from, to, payerBalanceAfter` |
| `FUNDS_HELD` | `payment:{id}` | `ledger` | `amount, from, trustedWallet, requestedWallet, escrowExpiresAt, challengeSha256` |
| `ROTATION_SIGNATURE_VERIFIED` | `payment:{id}` | `key-rotation` | `attempt, result, signerWallet, challengeSha256` |
| `ROTATION_SIGNATURE_REJECTED` | `payment:{id}` | `key-rotation` | `attempt, result, signerPublicKey?, challengeSha256` |
| `FUNDS_RELEASED` | `payment:{id}` | `ledger` | `amount, to` |
| `FUNDS_RETURNED` | `payment:{id}` | `ledger` | `amount, to, reason (SELF_AUTHORISATION·MAX_INVALID_ATTEMPTS·EXPIRED)` |
| `ESCROW_EXPIRED` | `payment:{id}` | `scheduler` / `demo` | `expiresAt, simulated` |
| `WALLET_ROTATED` | `wallet:{new}` | `key-rotation` | `supplierId, from, to` |
| `WALLET_FLAGGED` | `wallet:{addr}` | `key-rotation` | `wallet, reason` |
| `RULE_UPDATED` | `rule:{code}` | `compliance:{name}` | `rule, before{enabled,params,version}, after{…}` |

**Límite honesto, para el Q&A:** la cadena detecta ediciones, pero no impide una reescritura completa de todos los hashes por alguien con acceso total a la BD. La respuesta es anclar periódicamente el head hash fuera del sistema (banco contraparte o regulador) y firmarlo con una clave en HSM. Es un siguiente paso, no el MVP.

---

## 5. API REST congelada

| # | Endpoint | Para qué | OK | Dueño |
|---|---|---|---|---|
| 1 | `GET /api/state` | Todo lo que pinta la UI (polling 1 s) | `200 AppState` | Antonio |
| 2 | `POST /api/payments` | Crear pago → decisión | `201 PaymentView` | Antonio (usa el motor de Alejandro) |
| 3 | `POST /api/payments/{id}/rotation-authorization` | Firma del proveedor | `200 PaymentView` | Antonio (usa `verify` de Alejandro) |
| 4 | `POST /api/demo/payments/{id}/expire` | Simular vencimiento | `200 PaymentView` | Antonio |
| 5 | `POST /api/demo/reset` | Volver al estado inicial | `200 ResetResult` | Antonio |
| 6 | `POST /api/sim/sign` | Firmar como proveedor, atacante o manipulado | `200 SimSignature` | Alejandro |
| 7 | `GET /api/audit?limit=200` | Log completo | `200 AuditLog` | Alejandro |
| 8 | `GET /api/audit/verify` | Verificar la cadena | `200 ChainReport` | Alejandro |
| 9 | `PATCH /api/rules/{code}` | Editar regla en vivo | `200 RuleView` | Alejandro |
| 10 | `POST /api/demo/tamper` | Manipular un evento saltándose la app (respaldo para el vídeo) | `200 TamperResult` | Alejandro |
| 11 | `GET /api/health` | Ya existe; añadir `headHash` | `200` | Antonio |

Cada endpoint tiene su ejemplo completo en `mocks.json`: `state`, `audit`, `auditVerifyValid`, `auditVerifyTampered`, `createPaymentSettled`, `createPaymentHeld`, `createPaymentBlocked`, `simSignAttacker`, `simSignLegit`, `simSignTampered`, `authorizeBlockedSelfAuthorisation`, `authorizeReleased`, `authorizeInvalidStillHeld`, `expireReverted`, `patchRule`, `reset`, `tamper` y `errors.*`.

### 5.1 Tipos (fuente de verdad para el frontend)

```ts
type Money = string;   // "80000.00"
type Ts = string;      // ISO-8601 UTC

interface AppState {
  serverTime: Ts;                                   // para el offset de los contadores
  payer:   { partyId: string; legalName: string; wallet: string; balance: Money; currency: "GBPT" };
  ledger:  { currency: "GBPT"; totalSupply: Money; payerBalance: Money; inEscrow: Money; supplierBalances: Money; conserved: boolean };
  metrics: { protectedAmount: Money; protectedCount: number; byStatus: Record<PaymentStatus, number> };
  suppliers: { partyId: string; legalName: string; trustedWallet: string; kyb: Kyb }[];
  wallets:  WalletView[];
  payments: PaymentView[];                          // más reciente primero, máx. 50
  rules:    RuleView[];                             // por prioridad
  audit:    { length: number; headHash: string; chainValid: boolean; firstInvalidSeq: number | null;
              tail: AuditEventView[] };             // últimos 12, más reciente primero
}
interface Kyb { verified: boolean; country: string; bank: string; companyNo: string; incorporatedDaysAgo?: number }
interface WalletView { address: string; partyId: string; ownerName: string; bank: string; status: WalletStatus; balance: Money;
  kybVerified: boolean; label: string; flagReason: string | null; rotatedFrom: string | null; rotatedTo: string | null }
interface PaymentView {
  id: number; ref: string;                          // "PAY-0003"
  supplierId: string; supplierName: string;
  payeeWallet: string; payeeWalletStatus: WalletStatus; payeeWalletOwner: string; payeeKybVerified: boolean;
  amount: Money; currency: "GBPT"; invoiceRef: string;
  status: PaymentStatus; decision: DecisionView; escrow: EscrowView | null;
  createdAt: Ts; resolvedAt: Ts | null;
}
interface DecisionView { outcome: Outcome; evaluatedAt: Ts; checks: CheckView[] }
interface CheckView { code: string; name: string; result: CheckResult; ruleVersion: number; message: string; evidence: Record<string, unknown> }
interface EscrowView {
  trustedWallet: string; trustedPublicKey: string; requestedWallet: string;
  challenge: string; challengeSha256: string; expiresAt: Ts; maxInvalidAttempts: number;
  attempts: { at: Ts; result: RotationResult; signerPublicKey: string | null; message: string }[];
  resolution: { type: Resolution; at: Ts; message: string } | null;
}
interface RuleView { code: string; name: string; description: string; outcome: "HOLD" | "BLOCK"; enabled: boolean;
  priority: number; params: Record<string, number | string>; version: number; updatedBy: string; updatedAt: Ts }
interface AuditEventView { seq: number; eventType: string; entityRef: string; actor: string; occurredAt: Ts;
  payload: Record<string, unknown>; prevHash: string; hash: string }
interface SimSignature { paymentId: number; as: SimActor; signerWallet: string; signerPublicKey: string;
  signedMessage: string; signature: string; explanation: string }
interface ChainReport { valid: boolean; length: number; headHash: string; checkedAt: Ts; firstInvalidSeq: number | null;
  reason: null | "HASH_MISMATCH" | "BROKEN_LINK" | "SEQ_GAP";
  expectedHash: string | null;   // hash recalculado con el contenido actual de la fila
  storedHash: string | null }    // hash guardado en la columna
interface ResetResult { ok: true; resetAt: Ts; headHash: string; payments: number; auditLength: number }
interface TamperResult { tamperedSeq: number; field: "amount"; before: Money; after: Money; note: string }
```

### 5.2 Detalle por endpoint

**`POST /api/payments`**
- Cabeceras: `Content-Type: application/json` y `Idempotency-Key: <uuid>`. La clave es obligatoria; el frontend genera un `crypto.randomUUID()` por clic.
- Body: `{"supplierId":"ACME","payeeWallet":"tdgb:acmetrading:7731","amount":"80000.00","invoiceRef":"INV-AC-1147"}`. El pagador está implícito: `NORTHWIND`, la tesorería de la demo.
- `201` → PaymentView con `SETTLED`, `HELD` o `BLOCKED`.
- La misma key con el mismo body devuelve `200` y el mismo PaymentView, sin cobrar dos veces.
- Errores: `400 VALIDATION_ERROR` · `404 UNKNOWN_SUPPLIER` / `UNKNOWN_WALLET` · `409 IDEMPOTENCY_CONFLICT` · `422 INVALID_AMOUNT` / `INSUFFICIENT_FUNDS`.

**`POST /api/payments/{id}/rotation-authorization`**
- Body: `{"signature":"<base64 64 bytes>","submittedBy":"supplier-portal"}`.
- `200` → PaymentView en `RELEASED`, `BLOCKED`, o `HELD` con el intento `INVALID` registrado.
- Errores: `400 MALFORMED_SIGNATURE` (sin cambio de estado) · `404 PAYMENT_NOT_FOUND` · `409 PAYMENT_NOT_HELD`.

**`POST /api/demo/payments/{id}/expire`**
- Fija `expiresAt = now` y ejecuta el mismo método de devolución que el scheduler.
- `200` → `REVERTED`. Errores: `404` · `409 PAYMENT_NOT_HELD`.

**`POST /api/sim/sign`**
- Body: `{"paymentId":3,"as":"ATTACKER"}`.
- `200 SimSignature`. Errores: `404` · `409 PAYMENT_NOT_HELD`.
- El frontend encadena dos llamadas: `sim/sign` y, con su `signature`, `rotation-authorization`. La separación es deliberada: `sim/sign` es el lado del proveedor (fuera del banco) y `rotation-authorization` es nuestro producto.

**`PATCH /api/rules/{code}`**
- Body: `{"enabled":false}` o `{"params":{"maxAttempts":2}}`, más `"changedBy":"compliance:J.Patel"`. Los `params` se fusionan parcialmente.
- `200 RuleView`: `version + 1` y evento `RULE_UPDATED` con el antes y el después.
- Errores: `400 INVALID_PARAMS` · `404 UNKNOWN_RULE`.
- Validación de parámetros:
  - `maxAttempts`: entero entre 1 y 20.
  - `windowMinutes`: entero entre 1 y 1440.
  - `holdMinutes`: entero entre 1 y 10080.
  - `maxInvalidAttempts`: entero entre 1 y 10.
  - `minAmount`: importe ≥ 0.
- `holdMinutes` solo afecta a los escrows nuevos.

**`POST /api/demo/reset`**
- Sin body. Hace TRUNCATE y vuelve a sembrar (§6). `200 ResetResult`.

**`POST /api/demo/tamper`**
- Body opcional `{"seq":7}`; sin body, toma el último evento `FUNDS_*`.
- Hace un `UPDATE` directo con JdbcTemplate dividiendo `amount` entre 10, **sin** recalcular el hash. Simula a alguien con acceso directo a la BD.

**Polling del frontend:** `GET /api/state` cada 1 s (en pausa si la pestaña está oculta) y un `refetch` inmediato tras cada comando. Contadores: `remaining = expiresAt − (Date.now() + (serverTime − momentoDeRecepción))`.

---

## 6. Seed determinista
Lo ejecuta `DemoSeeder` al arrancar y en `POST /api/demo/reset`. Total supply: 1,000,000.00 GBPT, todo inicialmente en la tesorería.

| Party | Nombre | Rol | Notas |
|---|---|---|---|
| `NORTHWIND` | Northwind Manufacturing plc | PAYER | Tesorería |
| `ACME` | Acme Components Ltd | SUPPLIER | Objetivo del BEC |
| `BRIGHTWATER` | Brightwater Logistics Ltd | SUPPLIER | Pago verde |
| `KESTREL` | Kestrel Packaging Ltd | SUPPLIER | Escrow sembrado para enseñar el vencimiento |
| `ACME_TRADING` | Acme Trading Ltd | OTHER | Mula: KYB verificado, constituida hace 23 días, nombre parecido |
| `ORION` | Orion Global Trade Ltd | OTHER | En la lista de sanciones del Reino Unido (OFSI, simulada); solo para tests |

| Wallet | Dueño | Banco (ficticio) | Estado inicial | Saldo |
|---|---|---|---|---|
| `tdgb:northwind:treasury` | NORTHWIND | Thames Bank (sim) | TRUSTED | 1,000,000.00 |
| `tdgb:acme:op-01` | ACME | Albion Bank (sim) | TRUSTED | 0 |
| `tdgb:acme:op-02` | ACME | Mercia Bank (sim) | UNVERIFIED | 0 |
| `tdgb:acmetrading:7731` | ACME_TRADING | Severn Bank (sim) | UNVERIFIED | 0 |
| `tdgb:brightwater:main` | BRIGHTWATER | Thames Bank (sim) | TRUSTED | 0 |
| `tdgb:kestrel:main` | KESTREL | Albion Bank (sim) | TRUSTED | 0 |
| `tdgb:kestrel:new-19` | KESTREL | Severn Bank (sim) | UNVERIFIED | 0 |
| `tdgb:orion:01` | ORION | Severn Bank (sim) | FLAGGED ("UK sanctions list (OFSI, simulated)") | 0 |

- **Reglas:** las de §2, versión 1.
- **Pago sembrado:** después del resto del seed se crea **PAY-0001** *a través de `PaymentService`*, para que tenga decisión, challenge y audit reales: KESTREL, 15,000.00 a `tdgb:kestrel:new-19`, `INV-KP-0877` → HELD.
- **Resultado del reset:** `auditLength = 3` (DEMO_RESET, PAYMENT_DECIDED, FUNDS_HELD).

**Presets de la demo** (constantes del frontend: cero tecleo en la demo):

| Botón | supplierId | payeeWallet | amount | invoiceRef | Esperado |
|---|---|---|---|---|---|
| Brightwater · INV-BW-2231 | BRIGHTWATER | `tdgb:brightwater:main` | 12400.00 | INV-BW-2231 | SETTLED |
| Acme · "URGENT: please pay our new trading entity" | ACME | `tdgb:acmetrading:7731` | 80000.00 | INV-AC-1147 | HELD |
| Acme · confirmed bank migration | ACME | `tdgb:acme:op-02` | 45000.00 | INV-AC-1152 | HELD → RELEASED con firma legítima |
| Acme · retry to the email's wallet (opcional) | ACME | `tdgb:acmetrading:7731` | 30000.00 | INV-AC-1160 | BLOCKED (RISK_LIST, tras el flag) |

---

## 7. Fronteras Java entre Antonio y Alejandro
Se fijan en la primera media hora, juntos. Cada uno solo edita sus paquetes; los ficheros compartidos (`pom.xml`, `application.yml`, `CoreApplication`) los edita Antonio a petición.

```text
com.fis.hackathon
├── shared/    (pareja, H0)  Outcome, CheckResult, Check, Decision, PaymentIntent, RotationResult, SimActor, EscrowChallenge
├── ledger/    (Antonio)     entidades Party/Wallet/Payment, repos, LedgerService, LedgerLock, DemoSeeder
├── payments/  (Antonio)     PaymentService (create/authorize/expire), EscrowScheduler, PaymentController, DemoController
├── api/       (Antonio)     StateQueryService + StateController, DTO mappers, GlobalErrorHandler (ProblemDetail)
├── rules/     (Alejandro)   RuleEntity/Repo, DecisionEngineImpl, UnverifiedAttemptsQuery, RuleController
├── crypto/    (Alejandro)   DemoKeyStore, KeyRotationServiceImpl, SupplierSimulator, SimController
└── audit/     (Alejandro)   AuditEventEntity/Repo, AuditLogImpl, CanonicalJson, AuditController, TamperController
```

```java
// rules/  · lo llama PaymentService.create()
public interface DecisionEngine { Decision evaluate(PaymentIntent intent); }   // lee reglas y cuenta intentos; no escribe

// crypto/ · lo llama PaymentService.create() / authorize()
public interface KeyRotationService {
    String newNonce();
    String buildChallenge(long paymentId, String supplierId, String trustedWallet, String requestedWallet,
                          BigDecimal amount, String nonce, Instant expiresAt);
    RotationResult verify(String challenge, String signatureB64,
                          String trustedPublicKeyB64, String requestedPublicKeyB64);       // nunca lanza
}

// audit/  · lo llaman todos los servicios que mutan
public interface AuditLog {
    AuditEventEntity append(String eventType, String entityRef, String actor, Map<String, Object> payload); // dentro de la tx del llamador
    ChainReport verify();
}

// ledger/ · primera línea de todo @Transactional que muta
public interface LedgerLock { void acquire(); }   // SELECT pg_advisory_xact_lock(4242)
```

**Para no bloquearse:** en la H0, Alejandro deja stubs que compilan (el motor devuelve APPROVE; `append` hace un no-op que loguea). Antonio programa contra las interfaces desde el minuto 30 y en el CP1 se cambian los stubs por las implementaciones.

---

## 8. Perímetro (Nginx): lectura y escritura con límites separados

**Problema actual:** `5r/s burst 10` sobre todo `/api/` y por IP. El polling del dashboard, Swagger y el ataque de la demo comparten el mismo cubo desde la misma IP. En mitad de la demo el propio dashboard recibiría 429.

```nginx
# --- contexto http {} ---
map $request_method $api_write_key {
    default  $binary_remote_addr;
    GET      "";
    HEAD     "";
    OPTIONS  "";
}
limit_req_zone $binary_remote_addr zone=api_read:10m  rate=30r/s;
limit_req_zone $api_write_key      zone=api_write:10m rate=5r/s;   # clave vacía = no se contabiliza

# --- server {} ---
location /api/ {
    limit_req zone=api_read  burst=60 nodelay;
    limit_req zone=api_write burst=10 nodelay;
    limit_req_status 429;
    error_page 429 = @rate_limited;
    # ... mantened vuestro proxy_pass y proxy_set_header actuales
}
location @rate_limited {
    default_type application/json;
    add_header Retry-After 1 always;
    return 429 '{"type":"about:blank","title":"Too Many Requests","status":429,"code":"RATE_LIMITED","detail":"Too many requests from this client. Slow down."}';
}
```

**Ataque de la demo.** Se lanza contra el endpoint de autorización, porque la historia es la fuerza bruta de firmas, no `/health`. Va desde un contenedor aparte: tiene otra IP, así que el dashboard no se ve afectado. Hay que hacer `docker pull curlimages/curl` **hoy**, con WiFi.

```bash
NET=$(docker network ls --format '{{.Name}}' | grep -m1 fis)      # p. ej. fis-hackathon_default: comprobadlo
docker run --rm --network "$NET" --entrypoint sh curlimages/curl -c '
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "attempt $i -> HTTP %{http_code}\n" -X POST \
       -H "Content-Type: application/json" -d "{\"signature\":\"AAAA\"}" \
       http://fis-gateway/api/payments/999/rotation-authorization &
done; wait'
```

**Resultado esperado:** unos 11 × `404` (pasan el gateway; el pago 999 no existe, así que no hay efectos) y unos 14 × `429`.
**Frase para la demo:** *"Brute-forcing an Ed25519 signature is already infeasible; on top of that, the gateway cuts an attacker off after 10 attempts."*

---

## 9. Matriz de pruebas
La ejecuta Alejandro con Swagger o con `smoke.sh` (curl + jq). Cada dueño arregla lo suyo.

| # | Acción | Esperado |
|---|---|---|
| T1 | Reset → state | 1 pago HELD (PAY-0001); audit 3 eventos; `chainValid`; `conserved` |
| T2 | Pagar preset Brightwater | 201 SETTLED; tesorería −12,400; +2 eventos |
| T3 | Pagar preset Acme "URGENT" | 201 HELD; `escrow.challenge` con el formato §3.1; `inEscrow` +80,000; `conserved` |
| T4 | sim ATTACKER + authorize sobre T3 | 200 BLOCKED `BLOCKED_SELF_AUTHORISATION`; fondos de vuelta; 7731 FLAGGED; +3 eventos |
| T5 | Pagar otra vez a 7731 | 201 BLOCKED (RISK_LIST); no se mueve dinero |
| T6 | Preset Acme migración + sim LEGIT + authorize | RELEASED; op-02 TRUSTED, op-01 ROTATED; +3 eventos |
| T7 | Pagar a Acme en op-02 | SETTLED (cero fricción tras rotar) |
| T8 | Pagar a Acme en op-01 (retirada) | HELD (ya no es la TRUSTED) |
| T9 | sim TAMPERED + authorize | 200, sigue HELD, intento INVALID 1/3 |
| T10 | Reutilizar la firma de T6 en otro pago HELD | INVALID (el challenge incluye payment y nonce) |
| T11 | 3 firmas INVALID seguidas | BLOCKED `BLOCKED_MAX_ATTEMPTS`; fondos de vuelta |
| T12 | `expire` sobre PAY-0001 | REVERTED; fondos de vuelta |
| T13 | authorize o expire sobre un pago ya resuelto | 409 PAYMENT_NOT_HELD; nada cambia |
| T14 | La misma `Idempotency-Key` dos veces | 1 solo pago y 1 solo cargo; misma key con otro body → 409 |
| T15 | Firma `AAAA` | 400 MALFORMED_SIGNATURE; sin cambio de estado ni evento |
| T16 | PATCH VELOCITY `maxAttempts=1` + nuevo pago a wallet no verificada | RULE_UPDATED en audit; la decisión cita `ruleVersion: 2`; BLOCK si n > 1 |
| T17 | 20 POST /payments en paralelo | `conserved = true`; `audit/verify` válido (sin forks) |
| T18 | `docker restart fis-backend` | Reseed coherente; la firma LEGIT sigue funcionando (claves desde fichero) |
| T19 | Tamper (psql o endpoint) | En ≤1 s `chainValid=false` y `firstInvalidSeq` correcto; reset → válido |
| T20 | Ataque §8 mientras el dashboard hace polling | El ataque recibe 429; el dashboard no recibe ni uno |
| T21 | JUnit con `test-vectors.json` | Clasificación Ed25519 y hashes de audit idénticos a los vectores |

---

## 10. Guion de la demo (golden path, unos 2:15 de los 5:00)

| t | Pantalla | Acción | Lo que ve el jurado |
|---|---|---|---|
| 0:00 | Treasury | (estado tras el reset) | Saldo 985,000; 1 pago en escrow; cadena ✓ |
| 0:05 | Treasury | Preset Brightwater → Pay | SETTLED al instante: *"clean payments: zero friction"* |
| 0:20 | Treasury | Preset Acme "URGENT" → Pay | HELD en ámbar; tarjeta de decisión: *"KYB verified ✓ … but not Acme's registered wallet"*; contador; −80,000 → escrow |
| 0:50 | Supplier | *"Attacker tries to authorise"* | La firma es **válida**, pero con la clave equivocada → BLOCKED; dinero de vuelta; wallet en la lista de riesgo |
| 1:20 | Treasury | Preset Acme migración → Pay; Supplier → *"Sign with Acme's registered key"* | RELEASED; op-02 pasa a ser la wallet registrada de Acme |
| 1:50 | Treasury | PAY-0001 → *"Simulate expiry"* | REVERTED: el dinero vuelve sin que intervenga nadie |
| 2:00 | Audit | Terminal: `UPDATE` en psql (o el botón tamper) | La cabecera se pone roja sola: *"tampering detected at event #N"* |

**Si vais tarde, recortad en este orden:** expiry → tamper (pasa al vídeo) → el paso verde.
**Nunca se recortan:** el ámbar del BEC y la firma del atacante.

Comando de tamper (en el portátil de la demo):

```bash
docker exec -it fis-db psql -U fis_user -d fis_ledger -c \
"UPDATE audit_event SET payload = replace(payload,'\"amount\":\"80000.00\"','\"amount\":\"8000.00\"') WHERE event_type='FUNDS_HELD' AND payload LIKE '%80000.00%';"
```

---

## 11. Configuración y comprobaciones previas

```yaml
# application.yml (cambios)
spring:
  jpa.hibernate.ddl-auto: none          # el esquema lo manda schema.sql
  sql.init.mode: always
# Jackson de las vistas: por defecto (los null SÍ se envían: escrow:null, resolvedAt:null…).
# El mapper canónico del audit es otro ObjectMapper propio con NON_NULL + claves ordenadas (§4.2).
```

- **`.gitattributes`** en la raíz: `* text=auto eol=lf` y `*.cmd text eol=crlf`. Así se evita que `mvnw` o los `.sh` lleguen con CRLF desde Windows (`/bin/sh^M: bad interpreter`).
- **Portátil de Alejandro (Windows):**
  - Clonar en `C:\dev\fis-hackathon`, **fuera de OneDrive** (OneDrive bloquea `node_modules` y `target`, y la sincronización da conflictos).
  - Los scripts bash se ejecutan con Git Bash o WSL.
- **Antes de salir del recinto, con su WiFi, en todos los portátiles:**
  - `git pull`.
  - `docker compose build` y `docker pull curlimages/curl`.
  - `./mvnw -q dependency:go-offline`.
  - `npm ci` en `frontend/`.
  - Arrancar el stack una vez.
- **`depends_on` con `condition: service_healthy`** (`pg_isready`) entre el backend y `fis-db`. Si no, `schema.sql` puede ejecutarse antes de que Postgres acepte conexiones.
- **`smoke.sh` (Alejandro):** reset → T2 → T3 → T4 → T6 → T12 → verify, con salida OK/FAIL en menos de 10 s. Se ejecuta antes de cada ensayo y 20 minutos antes de la presentación.
