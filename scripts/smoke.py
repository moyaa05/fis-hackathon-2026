#!/usr/bin/env python3
"""
Smoke test de la demo (SPEC §9/§10): recorre el golden path contra el backend REAL a través de nginx
y dice OK/FAIL en cada paso. Termina con un reset para dejar la demo limpia.
Solo usa la librería estándar de Python (nada que instalar).
Las escrituras van espaciadas 0,25 s para no chocar con el límite de nginx (5 escrituras/s).
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE", "http://localhost:18080").rstrip("/")
TAMPER = "--tamper" in sys.argv
results = []


def call(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if method != "GET":
        time.sleep(0.25)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw
    except urllib.error.URLError as e:
        print(f"\nNo puedo conectar con {BASE}: {e.reason}. ¿Está el stack arrancado?")
        sys.exit(2)


def check(name, condition, detail=""):
    results.append(bool(condition))
    print(f"{'OK  ' if condition else 'FAIL'}  {name}" + ("" if condition else f"   -> {detail}"))
    return condition


def pay(supplier, wallet, amount, invoice, key=None):
    key = key or str(uuid.uuid4())
    return call("POST", "/api/payments",
                {"supplierId": supplier, "payeeWallet": wallet, "amount": amount, "invoiceRef": invoice},
                {"Idempotency-Key": key})


def sign_and_authorize(payment_id, actor):
    s, sig = call("POST", "/api/sim/sign", {"paymentId": payment_id, "as": actor})
    if not check(f"sim/sign {actor} para pago {payment_id}", s == 200 and isinstance(sig, dict) and sig.get("signature"), f"{s} {sig}"):
        return None, None
    return call("POST", f"/api/payments/{payment_id}/rotation-authorization",
                {"signature": sig["signature"], "submittedBy": "smoke-test"})


def main():
    print(f"Smoke test contra {BASE}\n")

    s, r = call("POST", "/api/demo/reset")
    check("reset", s == 200 and isinstance(r, dict) and r.get("ok") is True, f"{s} {r}")

    s, st = call("GET", "/api/state")
    check("state tras reset: 1 pago HELD (PAY-0001)", s == 200 and len(st.get("payments", [])) == 1
          and st["payments"][0]["status"] == "HELD", f"{s}")
    check("state tras reset: cadena válida y dinero conservado", s == 200 and st["audit"]["chainValid"] and st["ledger"]["conserved"], f"{st.get('audit')} {st.get('ledger')}")

    # VERDE
    key = str(uuid.uuid4())
    s, p2 = pay("BRIGHTWATER", "tdgb:brightwater:main", "12400.00", "INV-BW-2231", key)
    check("verde: Brightwater -> SETTLED", s == 201 and p2.get("status") == "SETTLED", f"{s} {p2}")
    s, p2b = pay("BRIGHTWATER", "tdgb:brightwater:main", "12400.00", "INV-BW-2231", key)
    check("idempotencia: misma clave -> mismo pago, sin cobrar dos veces", s in (200, 201) and p2b.get("id") == p2.get("id"), f"{s} {p2b}")

    # ÁMBAR + ATACANTE
    s, p3 = pay("ACME", "tdgb:acmetrading:7731", "80000.00", "INV-AC-1147")
    check("ámbar: Acme -> wallet del email -> HELD", s == 201 and p3.get("status") == "HELD" and p3.get("escrow"), f"{s} {p3}")
    challenge = (p3.get("escrow") or {}).get("challenge", "")
    check("challenge con formato FIS-KEYROT-v1", challenge.startswith("FIS-KEYROT-v1\npayment="), challenge[:40])
    s, r = sign_and_authorize(p3["id"], "ATTACKER")
    check("rojo: firma del atacante -> BLOCKED (SELF_AUTHORISATION)", s == 200 and r.get("status") == "BLOCKED"
          and (r.get("escrow") or {}).get("resolution", {}).get("type") == "BLOCKED_SELF_AUTHORISATION", f"{s} {r}")

    # LIBERACIÓN LEGÍTIMA
    s, p4 = pay("ACME", "tdgb:acme:op-02", "45000.00", "INV-AC-1152")
    check("ámbar: Acme -> wallet nueva legítima -> HELD", s == 201 and p4.get("status") == "HELD", f"{s} {p4}")
    s, r = sign_and_authorize(p4["id"], "LEGIT_SUPPLIER")
    check("verde: firma con la clave registrada -> RELEASED", s == 200 and r.get("status") == "RELEASED", f"{s} {r}")
    s, r = call("POST", f"/api/payments/{p4['id']}/rotation-authorization", {"signature": "AAAA"})
    check("pago ya resuelto -> 409 PAYMENT_NOT_HELD (o 400 si valida la firma antes)", s in (400, 409), f"{s} {r}")

    # VENCIMIENTO
    s, r = call("POST", "/api/demo/payments/1/expire")
    check("vencimiento de PAY-0001 -> REVERTED", s == 200 and r.get("status") == "REVERTED", f"{s} {r}")

    # APRENDIZAJE: la wallet del atacante ya está en la lista de riesgo
    s, p5 = pay("ACME", "tdgb:acmetrading:7731", "30000.00", "INV-AC-1160")
    check("reintento a la wallet del atacante -> BLOCKED (RISK_LIST)", s == 201 and p5.get("status") == "BLOCKED", f"{s} {p5}")

    # ESTADO FINAL
    s, st = call("GET", "/api/state")
    check("dinero conservado al final", s == 200 and st["ledger"]["conserved"], f"{st.get('ledger')}")
    s, v = call("GET", "/api/audit/verify")
    check("cadena de audit válida", s == 200 and v.get("valid") is True, f"{s} {v}")

    if TAMPER:
        s, t = call("POST", "/api/demo/tamper")
        check("tamper aplicado", s == 200 and t.get("tamperedSeq"), f"{s} {t}")
        s, v = call("GET", "/api/audit/verify")
        check(f"manipulación detectada en el evento #{(v or {}).get('firstInvalidSeq')}",
              s == 200 and v.get("valid") is False and v.get("reason") == "HASH_MISMATCH", f"{s} {v}")

    s, r = call("POST", "/api/demo/reset")
    check("reset final (demo limpia)", s == 200, f"{s} {r}")

    passed, total = sum(results), len(results)
    print(f"\n{'TODO OK' if passed == total else 'HAY FALLOS'}: {passed}/{total}")
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
