#!/usr/bin/env python3
"""
Matriz de pruebas de la SPEC §9 (T1–T17 y T19), automatizada. Complementa a smoke.py:
smoke.py recorre el golden path; este script cubre lo que smoke.py NO prueba
(T7–T11, T13–T17: reutilización de firmas, 3 intentos inválidos, idempotencia con otro body,
edición de reglas en vivo y 20 pagos en paralelo).

    python3 scripts/matrix.py              -> matriz T1–T17 + T19
    python3 scripts/matrix.py --extra      -> además, las regresiones de la auditoría del 25-nov
    BASE=http://localhost:18080 python3 scripts/matrix.py   -> a través de nginx (más lento; se salta T17)

Por defecto va DIRECTO al backend (http://localhost:8081, sin nginx): T17 lanza 20 POST a la vez
y el gateway, con razón, los cortaría a 5 escrituras/s. T18 (reinicio), T20 (attack.sh) y T21 (JUnit)
se prueban aparte. Solo usa la librería estándar. Termina con un reset: deja la demo limpia.
"""
import concurrent.futures
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE", "http://localhost:8081").rstrip("/")
EXTRA = "--extra" in sys.argv
VIA_GATEWAY = ":18080" in BASE
results = []


def call(method, path, body=None, headers=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if VIA_GATEWAY and method != "GET":
        time.sleep(0.25)                     # nginx: 5 escrituras/s
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, text
    except urllib.error.URLError as e:
        print(f"\nNo puedo conectar con {BASE}: {e.reason}. ¿Está el stack arrancado?")
        sys.exit(2)


def check(name, condition, detail=""):
    results.append(bool(condition))
    print(f"{'OK  ' if condition else 'FAIL'}  {name}" + ("" if condition else f"   -> {str(detail)[:400]}"))
    return condition


def reset():
    s, r = call("POST", "/api/demo/reset")
    if s != 200:
        print(f"\nEl reset ha fallado ({s}): {r}"); sys.exit(1)
    return r


def state():
    return call("GET", "/api/state")[1]


def pay(supplier, wallet, amount, invoice, key=None):
    return call("POST", "/api/payments",
                {"supplierId": supplier, "payeeWallet": wallet, "amount": amount, "invoiceRef": invoice},
                {"Idempotency-Key": key or str(uuid.uuid4())})


def sign(payment_id, actor):
    return call("POST", "/api/sim/sign", {"paymentId": payment_id, "as": actor})[1]


def authorize(payment_id, signature):
    return call("POST", f"/api/payments/{payment_id}/rotation-authorization",
                {"signature": signature, "submittedBy": "matrix-test"})


def sign_and_authorize(payment_id, actor):
    return authorize(payment_id, sign(payment_id, actor)["signature"])


def wallet_status(st, address):
    return next(w["status"] for w in st["wallets"] if w["address"] == address)


def check_of(payment, code):
    return next(c for c in payment["decision"]["checks"] if c["code"] == code)


def matrix():
    r = reset(); st = state()
    check("T1  reset: 1 pago HELD, 3 eventos, cadena válida, dinero conservado",
          len(st["payments"]) == 1 and st["payments"][0]["status"] == "HELD" and r["auditLength"] == 3
          and st["audit"]["chainValid"] and st["ledger"]["conserved"], (r, st["ledger"]))

    n0 = st["audit"]["length"]
    s, p2 = pay("BRIGHTWATER", "tdgb:brightwater:main", "12400.00", "INV-BW-2231"); st = state()
    check("T2  Brightwater -> SETTLED, tesorería -12.400, +2 eventos",
          s == 201 and p2["status"] == "SETTLED" and st["payer"]["balance"] == "972600.00"
          and st["audit"]["length"] == n0 + 2, (s, st["payer"]))

    s, p3 = pay("ACME", "tdgb:acmetrading:7731", "80000.00", "INV-AC-1147"); st = state()
    lines = ((p3.get("escrow") or {}).get("challenge") or "").split("\n")
    check("T3  Acme URGENT -> HELD, challenge §3.1 (9 líneas), escrow +80.000, conservado",
          s == 201 and p3["status"] == "HELD" and len(lines) == 9 and lines[0] == "FIS-KEYROT-v1"
          and lines[1] == f"payment={p3['id']}" and lines[5] == "amount=80000.00" and lines[6] == "currency=GBPT"
          and st["ledger"]["inEscrow"] == "95000.00" and st["ledger"]["conserved"], (lines, st["ledger"]))

    n0 = st["audit"]["length"]
    s, r = sign_and_authorize(p3["id"], "ATTACKER"); st = state()
    check("T4  firma del atacante -> BLOCKED_SELF_AUTHORISATION, fondos de vuelta, 7731 FLAGGED, +3 eventos",
          s == 200 and r["status"] == "BLOCKED" and r["escrow"]["resolution"]["type"] == "BLOCKED_SELF_AUTHORISATION"
          and wallet_status(st, "tdgb:acmetrading:7731") == "FLAGGED" and st["payer"]["balance"] == "972600.00"
          and st["audit"]["length"] == n0 + 3, (s, r.get("status"), st["payer"]))

    s, p5 = pay("ACME", "tdgb:acmetrading:7731", "30000.00", "INV-AC-1160"); st = state()
    check("T5  otro pago a 7731 -> BLOCKED por RISK_LIST, no se mueve dinero",
          s == 201 and p5["status"] == "BLOCKED" and check_of(p5, "RISK_LIST")["result"] == "BLOCK"
          and st["payer"]["balance"] == "972600.00", p5.get("decision"))

    n0 = st["audit"]["length"]
    s, p6 = pay("ACME", "tdgb:acme:op-02", "45000.00", "INV-AC-1152")
    s, r = sign_and_authorize(p6["id"], "LEGIT_SUPPLIER"); st = state()
    check("T6  migración + firma legítima -> RELEASED, op-02 TRUSTED, op-01 ROTATED, +3 eventos",
          s == 200 and r["status"] == "RELEASED" and wallet_status(st, "tdgb:acme:op-02") == "TRUSTED"
          and wallet_status(st, "tdgb:acme:op-01") == "ROTATED" and st["audit"]["length"] == n0 + 2 + 3, (s, r.get("status")))

    s, p7 = pay("ACME", "tdgb:acme:op-02", "1000.00", "INV-AC-1170")
    check("T7  pago a Acme en op-02 -> SETTLED (cero fricción tras rotar)", s == 201 and p7["status"] == "SETTLED", p7)

    # OJO: en el orden literal de la SPEC, T3 + T5 + T6 + T8 son 4 intentos de Acme a wallets no registradas
    # en 30 min y VELOCITY_UNVERIFIED (máx. 3) bloquea T8. Por eso T8–T11 parten de un reset.
    reset()
    s, pm = pay("ACME", "tdgb:acme:op-02", "45000.00", "INV-AC-1152")
    legit_signature = sign(pm["id"], "LEGIT_SUPPLIER")["signature"]
    authorize(pm["id"], legit_signature)

    s, p8 = pay("ACME", "tdgb:acme:op-01", "2000.00", "INV-AC-1171")
    check("T8  pago a Acme en op-01 (retirada) -> HELD", s == 201 and p8["status"] == "HELD", p8.get("decision"))

    s, r = sign_and_authorize(p8["id"], "TAMPERED")
    check("T9  firma TAMPERED -> 200, sigue HELD, intento INVALID 1/3",
          s == 200 and r["status"] == "HELD" and [a["result"] for a in r["escrow"]["attempts"]] == ["INVALID"], r)

    s, r = authorize(p8["id"], legit_signature)
    check("T10 reutilizar la firma de otro pago -> INVALID (el challenge lleva payment y nonce)",
          s == 200 and r["status"] == "HELD" and r["escrow"]["attempts"][-1]["result"] == "INVALID", r)

    before = float(state()["payer"]["balance"])
    s, r = sign_and_authorize(p8["id"], "TAMPERED"); st = state()
    check("T11 tercer INVALID -> BLOCKED_MAX_ATTEMPTS, fondos de vuelta",
          s == 200 and r["status"] == "BLOCKED" and r["escrow"]["resolution"]["type"] == "BLOCKED_MAX_ATTEMPTS"
          and float(st["payer"]["balance"]) == before + 2000 and st["ledger"]["conserved"], (s, r.get("status")))

    s, r = call("POST", "/api/demo/payments/1/expire"); st = state()
    check("T12 expire PAY-0001 -> REVERTED, fondos de vuelta",
          s == 200 and r["status"] == "REVERTED" and r["escrow"]["resolution"]["type"] == "RETURNED_EXPIRED"
          and st["ledger"]["inEscrow"] == "0.00" and st["ledger"]["conserved"], (s, st["ledger"]))

    n0 = st["audit"]["length"]
    s1, r1 = authorize(1, legit_signature)
    s2, _ = call("POST", "/api/demo/payments/1/expire")
    s3, _ = authorize(pm["id"], legit_signature)
    check("T13 authorize/expire sobre pago resuelto -> 409 PAYMENT_NOT_HELD, nada cambia",
          (s1, s2, s3) == (409, 409, 409) and r1.get("code") == "PAYMENT_NOT_HELD" and state()["audit"]["length"] == n0, (s1, s2, s3, r1))

    key = str(uuid.uuid4()); before = float(state()["payer"]["balance"])
    s1, a = pay("BRIGHTWATER", "tdgb:brightwater:main", "100.00", "INV-ID-1", key)
    s2, b = pay("BRIGHTWATER", "tdgb:brightwater:main", "100.00", "INV-ID-1", key)
    s3, c = pay("BRIGHTWATER", "tdgb:brightwater:main", "200.00", "INV-ID-1", key)
    check("T14 misma Idempotency-Key -> 201 y luego 200 (un solo cargo); otro body -> 409",
          (s1, s2, s3) == (201, 200, 409) and a["id"] == b["id"] and c.get("code") == "IDEMPOTENCY_CONFLICT"
          and float(state()["payer"]["balance"]) == before - 100, (s1, s2, s3))

    s, pk = pay("KESTREL", "tdgb:kestrel:new-19", "500.00", "INV-KP-1"); n0 = state()["audit"]["length"]
    s, r = authorize(pk["id"], "AAAA"); st = state()
    check("T15 firma 'AAAA' -> 400 MALFORMED_SIGNATURE, sin cambio de estado ni evento",
          s == 400 and r.get("code") == "MALFORMED_SIGNATURE" and st["audit"]["length"] == n0
          and next(p for p in st["payments"] if p["id"] == pk["id"])["escrow"]["attempts"] == [], (s, r))

    s, rule = call("PATCH", "/api/rules/VELOCITY_UNVERIFIED", {"params": {"maxAttempts": 1}, "changedBy": "compliance:J.Patel"})
    ev = state()["audit"]["tail"][0]
    s2, q = pay("KESTREL", "tdgb:kestrel:new-19", "600.00", "INV-KP-2"); vel = check_of(q, "VELOCITY_UNVERIFIED")
    check("T16 PATCH maxAttempts=1 -> RULE_UPDATED en audit, decisión con ruleVersion 2, BLOCK si n > 1",
          s == 200 and rule["version"] == 2 and ev["eventType"] == "RULE_UPDATED" and ev["actor"] == "compliance:J.Patel"
          and ev["payload"]["before"]["version"] == 1 and ev["payload"]["after"]["version"] == 2
          and vel["ruleVersion"] == 2 and vel["result"] == "BLOCK" and q["status"] == "BLOCKED", (rule, ev, vel))

    if VIA_GATEWAY:
        print("--    T17 omitido: a través de nginx el límite de 5 escrituras/s corta la ráfaga (es lo correcto)")
    else:
        reset()
        targets = [("BRIGHTWATER", "tdgb:brightwater:main"), ("ACME", "tdgb:acme:op-02"),
                   ("KESTREL", "tdgb:kestrel:new-19"), ("ACME", "tdgb:acmetrading:7731")]
        with concurrent.futures.ThreadPoolExecutor(20) as pool:
            codes = list(pool.map(lambda i: pay(*targets[i % 4], f"{100 + i}.00", f"INV-PAR-{i}")[0], range(20)))
        st = state(); verify = call("GET", "/api/audit/verify")[1]
        seqs = sorted(e["seq"] for e in call("GET", "/api/audit?limit=500")[1]["events"])
        check("T17 20 POST /payments en paralelo -> conservado, cadena válida, sin huecos ni forks",
              all(c == 201 for c in codes) and st["ledger"]["conserved"] and verify["valid"]
              and seqs == list(range(1, len(seqs) + 1)) and len(st["payments"]) == 21, (codes, st["ledger"], verify))

    reset(); pay("ACME", "tdgb:acmetrading:7731", "80000.00", "INV-AC-1147")
    s, t = call("POST", "/api/demo/tamper"); st = state()
    detected = s == 200 and st["audit"]["chainValid"] is False and st["audit"]["firstInvalidSeq"] == t.get("tamperedSeq")
    reset()
    check("T19 tamper -> chainValid=false y firstInvalidSeq correcto; reset -> válida",
          detected and state()["audit"]["chainValid"] is True, (t, st["audit"]["chainValid"], st["audit"]["firstInvalidSeq"]))


def extra():
    """Regresiones de la auditoría del 25-nov (pasan con el lote A aplicado)."""
    print("\n-- regresiones de la auditoría (lote A) --")
    reset()
    for i in range(55):
        pay("BRIGHTWATER", "tdgb:brightwater:main", "1.00", f"INV-F-{i}")
    st = state()
    check("A2  más de 50 pagos con un HELD antiguo: 'conserved' sigue en true y el escrow cuenta",
          st["ledger"]["conserved"] and st["ledger"]["inEscrow"] == "15000.00" and st["metrics"]["byStatus"]["SETTLED"] == 55, st["ledger"])

    reset(); st = state()
    check("A2  'protected' no cuenta pagos HELD (0 tras el reset, como en mocks.json)",
          st["metrics"]["protectedAmount"] == "0.00" and st["metrics"]["protectedCount"] == 0, st["metrics"])

    bodies = [
        ("JSON mal formado", call("POST", "/api/payments", raw=b'{"supplierId":', headers={"Idempotency-Key": "k"})),
        ("id no numérico", call("POST", "/api/payments/abc/rotation-authorization", {"signature": "AAAA"})),
        ("actor inexistente en sim/sign", call("POST", "/api/sim/sign", {"paymentId": 1, "as": "HACKER"})),
        ("método no soportado", call("GET", "/api/payments")),
        ("ruta inexistente", call("GET", "/api/nope")),
        ("invoiceRef de 41 caracteres", pay("ACME", "tdgb:acme:op-01", "1.00", "X" * 41)),
        ("Idempotency-Key de 81 caracteres", pay("ACME", "tdgb:acme:op-01", "1.00", "I", "K" * 81)),
    ]
    bad = [(n, s, b) for n, (s, b) in bodies if not (400 <= s < 500 and isinstance(b, dict) and b.get("code") and b.get("detail"))]
    check("A3  todos los errores salen como problem+json con code y detail (ningún 500)", not bad, bad)

    s, r = pay("ACME_TRADING", "tdgb:acmetrading:7731", "1000.00", "INV-ROLE")
    check("A6  un tercero (ACME_TRADING) no vale como proveedor -> 404 UNKNOWN_SUPPLIER", s == 404 and r.get("code") == "UNKNOWN_SUPPLIER", (s, r))

    key = str(uuid.uuid4())
    s1, _ = pay("BRIGHTWATER", "tdgb:brightwater:main", "12400.00", "INV-N", key)
    s2, _ = pay("BRIGHTWATER", "tdgb:brightwater:main", "12400.0", "INV-N", key)
    check("A6  idempotencia: '12400.00' y '12400.0' son el mismo body", (s1, s2) == (201, 200), (s1, s2))

    reset()
    _, a = pay("ACME", "tdgb:acme:op-02", "45000.00", "INV-AC-1152"); _, b = pay("ACME", "tdgb:acme:op-02", "45000.00", "INV-AC-1152")
    sign_and_authorize(a["id"], "LEGIT_SUPPLIER"); s, r = sign_and_authorize(b["id"], "LEGIT_SUPPLIER")
    rotations = [e for e in call("GET", "/api/audit?limit=100")[1]["events"] if e["eventType"] == "WALLET_ROTATED"]
    op02 = next(w for w in state()["wallets"] if w["address"] == "tdgb:acme:op-02")
    check("A7  doble clic en 'migración': un solo WALLET_ROTATED y rotatedFrom = op-01",
          s == 200 and r["status"] == "RELEASED" and len(rotations) == 1 and op02["rotatedFrom"] == "tdgb:acme:op-01", (rotations, op02))

    reset()
    _, a = pay("ACME", "tdgb:acmetrading:7731", "80000.00", "INV-AC-1147"); _, b = pay("ACME", "tdgb:acmetrading:7731", "80000.00", "INV-AC-1147")
    sign_and_authorize(a["id"], "ATTACKER"); s, r = sign_and_authorize(b["id"], "LEGIT_SUPPLIER"); st = state()
    mule = next(w for w in st["wallets"] if w["address"] == "tdgb:acmetrading:7731")
    check("A9  wallet marcada con otro pago aún retenido: ninguna firma lo libera (409 DESTINATION_FLAGGED)",
          s == 409 and r.get("code") == "DESTINATION_FLAGGED" and mule["balance"] == "0.00" and st["ledger"]["conserved"], (s, r, mule))

    h = call("GET", "/api/health")[1]
    check("A8  /api/health devuelve la cabeza real de la cadena", h.get("headHash") == st["audit"]["headHash"] and h.get("chainValid") is True, h)


def main():
    print(f"Matriz T1–T19 contra {BASE}\n")
    matrix()
    if EXTRA:
        extra()
    reset()
    passed, total = sum(results), len(results)
    print(f"\n{'TODO OK' if passed == total else 'HAY FALLOS'}: {passed}/{total}   (reset final hecho: demo limpia)")
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
