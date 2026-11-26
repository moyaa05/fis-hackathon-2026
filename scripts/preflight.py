#!/usr/bin/env python3
"""
Comprobación previa a la demo. Un comando, un veredicto: GO o NO-GO.

    python3 scripts/preflight.py            -> completa (unos 25 s): contenedores, salud, smoke, matriz y reset final
    python3 scripts/preflight.py --quick    -> solo mira (2 s): NO crea pagos ni resetea; sirve con la demo ya preparada
    (--no-docker se salta la comprobación de contenedores)

La completa deja la demo recién reseteada y dice hasta qué hora vale ese reset
(el pago sembrado PAY-0001 se devuelve solo a los 120 minutos).
Solo usa la librería estándar de Python. Variables: BASE (gateway), DIRECT (backend sin nginx).
"""
import datetime as dt
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:18080").rstrip("/")
DIRECT = os.environ.get("DIRECT", "http://localhost:8081").rstrip("/")
UI = os.environ.get("UI", BASE).rstrip("/")
QUICK = "--quick" in sys.argv
NO_DOCKER = "--no-docker" in sys.argv
HERE = os.path.dirname(os.path.abspath(__file__))
CONTAINERS = ["fis-db", "fis-backend", "fis-frontend", "fis-gateway"]
fails, warns = [], []


def line(mark, text):
    print(f"{mark:5s} {text}")


def ok(text):
    line("OK", text)


def fail(text, fix=""):
    fails.append(text)
    line("FAIL", text + (f"\n      -> {fix}" if fix else ""))


def warn(text, fix=""):
    warns.append(text)
    line("AVISO", text + (f"\n      -> {fix}" if fix else ""))


def get(url, timeout=5):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:  # conexión rechazada, timeout…
        return 0, str(e)


def get_json(url):
    status, body = get(url)
    try:
        return status, json.loads(body)
    except ValueError:
        return status, None


def run(cmd, timeout=120):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout + p.stderr).strip()
    except Exception as e:
        return 1, str(e)


def check_containers():
    if NO_DOCKER or not shutil.which("docker"):
        warn("Sin docker: me salto la comprobación de contenedores")
        return
    for name in CONTAINERS:
        code, out = run(["docker", "inspect", "-f", "{{.State.Running}} {{.RestartCount}}", name], timeout=15)
        if code != 0 or not out.startswith("true"):
            fail(f"Contenedor {name} no está en marcha", "docker compose up -d")
        else:
            restarts = out.split()[-1]
            ok(f"Contenedor {name} en marcha") if restarts == "0" else warn(
                f"Contenedor {name} en marcha, pero se ha reiniciado {restarts} veces", f"docker logs --tail 50 {name}")
    code, _ = run(["docker", "image", "inspect", "curlimages/curl"], timeout=15)
    if code != 0:
        warn("Falta la imagen curlimages/curl: attack.sh no funcionará sin WiFi", "docker pull curlimages/curl")
    else:
        ok("Imagen curlimages/curl descargada (attack.sh funciona sin red)")


def check_endpoints():
    status, health = get_json(f"{BASE}/api/health")
    if status != 200 or not health:
        fail(f"El gateway no responde en {BASE}/api/health (HTTP {status})",
             "docker compose up -d && docker compose restart gateway")
        return False
    ok(f"Gateway + backend responden ({BASE})")
    if "headHash" not in health:
        fail("El backend en marcha es antiguo (health sin headHash)", "docker compose up -d --build backend && docker compose restart gateway")

    status, body = get(f"{UI}/")
    if status == 200 and 'id="root"' in body:
        ok("La interfaz se sirve a través del gateway")
    else:
        fail(f"La interfaz no se sirve en {UI}/ (HTTP {status})", "docker compose up -d --build frontend && docker compose restart gateway")

    status, _ = get_json(f"{DIRECT}/api/health")
    if status == 200:
        ok(f"Backend directo accesible ({DIRECT}) para matrix.py")
    else:
        warn(f"Backend directo no accesible en {DIRECT}: matrix.py no podrá ejecutarse")
    return True


def check_state(expect_fresh):
    status, st = get_json(f"{BASE}/api/state")
    if status != 200 or not st:
        fail(f"GET /api/state falla (HTTP {status})")
        return
    if st["audit"]["chainValid"]:
        ok(f"Cadena de audit válida ({st['audit']['length']} eventos)")
    else:
        fail(f"Cadena de audit ROTA en el evento #{st['audit']['firstInvalidSeq']}", "Reset demo (dos clics) o POST /api/demo/reset")
    if st["ledger"]["conserved"]:
        ok(f"Dinero conservado ({st['ledger']['totalSupply']} GBPT)")
    else:
        fail(f"El ledger no cuadra: {st['ledger']}")
    flagged = [w["address"] for w in st["wallets"] if w["status"] == "FLAGGED" and w["address"] != "tdgb:orion:01"]
    rules_changed = [r["code"] for r in st["rules"] if r["version"] != 1 or not r["enabled"]]
    payments = st["payments"]
    fresh = (len(payments) == 1 and payments[0]["status"] == "HELD" and st["audit"]["length"] == 3
             and not flagged and not rules_changed)
    if fresh:
        expires = dt.datetime.strptime(payments[0]["escrow"]["expiresAt"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
        now = dt.datetime.strptime(st["serverTime"][:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=dt.timezone.utc)
        minutes = int((expires - now).total_seconds() // 60)
        local = expires.astimezone().strftime("%H:%M")
        if minutes >= 20:
            ok(f"Demo recién reseteada: PAY-0001 en escrow. Vale hasta las {local} (quedan {minutes} min)")
        else:
            warn(f"PAY-0001 se devuelve solo a las {local} (quedan {minutes} min)", "Reset demo justo antes de subir")
    else:
        detail = f"{len(payments)} pagos, {st['audit']['length']} eventos"
        if flagged:
            detail += f", wallets marcadas: {', '.join(flagged)}"
        if rules_changed:
            detail += f", reglas cambiadas: {', '.join(rules_changed)}"
        (fail if expect_fresh else warn)(f"La demo NO está en su estado inicial ({detail})", "Reset demo (dos clics) antes de subir")


def run_suite(name, args, expected):
    env_cmd = [sys.executable, os.path.join(HERE, args[0])] + args[1:]
    code, out = run(env_cmd, timeout=180)
    last = out.splitlines()[-1] if out else ""
    if code == 0 and expected in last:
        ok(f"{name}: {last.strip()}")
    else:
        failed = [l for l in out.splitlines() if l.startswith("FAIL")][:3]
        fail(f"{name}: {last.strip() or 'sin salida'}", " | ".join(failed) or f"python3 scripts/{' '.join(args)}")


def main():
    print(f"Preflight {'rápido' if QUICK else 'completo'} · {dt.datetime.now().strftime('%H:%M:%S')} · gateway {BASE}\n")
    check_containers()
    if check_endpoints():
        if QUICK:
            check_state(expect_fresh=False)
        else:
            os.environ["BASE"] = BASE
            run_suite("smoke (recorrido completo por nginx)", ["smoke.py", "--tamper"], "20/20")
            os.environ["BASE"] = DIRECT
            run_suite("matriz T1–T19 + regresiones", ["matrix.py", "--extra"], "26/26")
            os.environ["BASE"] = BASE
            check_state(expect_fresh=True)

    print()
    if fails:
        print(f"NO-GO · {len(fails)} fallo(s), {len(warns)} aviso(s). Arregla lo marcado con FAIL y vuelve a lanzarlo.")
        sys.exit(1)
    print(f"GO · todo correcto{f' ({len(warns)} aviso(s))' if warns else ''}. "
          "Abre http://localhost:18080 en Chrome, F11, zoom 150 %.")


if __name__ == "__main__":
    main()
