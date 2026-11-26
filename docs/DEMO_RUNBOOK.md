# Guion de operador de la demo en vivo

Para quien lleva el ratón. Unos 2:15 de los 5:00 del pitch.

Esta ruta está comprobada clic a clic contra el backend real con un navegador automatizado, a 1280×720, 1280×633 y 1920×1080: todos los botones quedan a la vista sin hacer scroll, las cifras de abajo son las que salen y no hay ninguna respuesta de error. Lo que **no** está comprobado es el proyector de la sala: hay que ensayarlo allí una vez.

## 1. Antes de salir

**Con WiFi, antes de ir a la sala**

```bash
git pull
docker compose up -d --build
docker compose restart gateway
docker pull curlimages/curl
python3 scripts/preflight.py        # tiene que acabar en GO
```

**20 minutos antes**

- Portátil enchufado y sin suspensión automática.
- Cerrar todo menos Chrome y un terminal.
- `python3 scripts/preflight.py` otra vez. Acaba en GO, deja la demo recién reiniciada y dice hasta qué hora vale ese reinicio (el pago sembrado caduca a los 120 minutos).

**2 minutos antes**

- Chrome en `http://localhost:18080`, pantalla completa (F11).
- Zoom: en un proyector 1080p, 150 %. Regla práctica: los cuatro pagos del *payment run* en una sola fila y la columna *Audit chain* visible a la derecha.
- **Reset demo** (dos clics) y comprobar la cabecera:

| Treasury balance | Locked in escrow | Funds protected | Estado |
|---|---|---|---|
| £985,000.00 | £15,000.00 | £0.00 | Ledger balanced · 3 eventos en la cadena |

Si la cabecera no dice exactamente eso, no se empieza: otro reset.

## 2. La ruta

Un clic, esperar a ver el resultado (un segundo), siguiente clic. Nunca doble clic.

| # | Qué pulsar | Qué tiene que verse | Cabecera después |
|---|---|---|---|
| 1 | Tarjeta **Brightwater Logistics** | Tarjeta de decisión verde, *Paid instantly*. La factura queda marcada PAID. | Treasury £972,600.00 |
| 2 | Tarjeta **Acme Components — "URGENT: please pay our new trading entity"** | Ámbar, HELD. Tres comprobaciones; la tercera dice que esa wallet no es la registrada de Acme. Cuenta atrás del escrow. | Treasury £892,600.00 · Escrow £95,000.00 |
| 3 | **Supplier side →** (en la franja ámbar) y luego **Attacker · key of the new wallet** | Panel *Verification*: la firma es válida, pero está hecha con una clave distinta. BLOCKED. *A wallet cannot authorise itself*. La wallet entra en la lista de riesgo. | Treasury £972,600.00 · Escrow £15,000.00 · Funds protected · 1 £80,000.00 |
| 4 | Tecla **1** → tarjeta **Acme — moving to a new bank** → tecla **2** → **Acme Components Ltd · registered key** | Al pagar: HELD (Treasury £927,600.00 · Escrow £60,000.00). Al firmar: RELEASED y la wallet nueva pasa a ser la registrada de Acme. | Treasury £927,600.00 · Escrow £15,000.00 |
| 5 | Sin cambiar de pestaña (PAY-0001 se abre solo): **Nobody · skip to expiry** | REVERTED. *No authorisation before expiry*. El dinero vuelve sin que intervenga nadie. | Treasury £942,600.00 · Escrow £0.00 · Funds protected · 2 £95,000.00 |
| 6 | Tecla **3** → **Edit a record directly in the database (demo)** | La barra superior se pone roja: *Audit chain tampering detected at event #N*. En la tabla, la fila alterada sale en rojo con MISMATCH. | Barra roja |

Frases de apoyo, una por paso (en inglés, el jurado es de Londres):

1. "A known supplier, its registered wallet. Settled at once, no friction."
2. "Same supplier, a verified company, an urgent email. But this is not Acme's registered wallet. The money leaves treasury and stops in escrow."
3. "The attacker controls the new wallet, so he signs. The signature is mathematically valid. It is simply the wrong key. A wallet cannot authorise itself: blocked, money back, wallet on the risk list."
4. "When Acme really moves bank, it signs with the key it already holds. Released, and the new wallet becomes the registered one."
5. "If nobody signs, the escrow expires and the money comes back on its own."
6. "Every step is hash-chained. Someone edits one amount directly in the database, and the system flags it within a second."

Variante del paso 6 con más efecto, solo si está ensayada: dejar la pantalla en Treasury y lanzar desde el terminal `curl -X POST http://localhost:18080/api/demo/tamper`. La barra se pone roja sola.

## 3. Lo que no se hace en directo

- **Clics rápidos.** El gateway corta más de 5 escrituras por segundo desde la misma IP (respuesta 429). Medido en el portátil de la demo: 16 clics en 2 segundos, dos rechazados. A ritmo normal no aparece nunca.
- **Tocar las reglas** en Compliance, salvo que lo pidan en el Q&A. Cambian de versión y hay que reiniciar después.
- **Escribir en un campo y usar las teclas 1/2/3.** Con el foco en un campo, las teclas escriben; los atajos solo funcionan fuera.
- **Abrir la demo en dos portátiles contra el mismo backend.** Un reset de uno le cambia el estado al otro.

## 4. Si algo falla

| Lo que se ve | Causa | Qué hacer |
|---|---|---|
| Franja *Connection lost* | Backend o gateway caídos | Seguir hablando. Terminal: `docker compose restart gateway` (10 s). Si en 15 s no vuelve, vídeo. |
| *Too many requests from this client. Slow down.* | Doble clic o clics muy seguidos | Esperar dos segundos y repetir el clic. |
| La factura URGENT sale BLOCKED a la primera | No se hizo reset: la wallet sigue en la lista de riesgo del ensayo | Reset demo (dos clics) y repetir el paso 2. |
| En Supplier side no aparece PAY-0001 | Han pasado más de 120 minutos desde el reset y el escrow ya caducó solo | Reset demo. |
| Barra roja nada más abrir | Quedó un registro manipulado del ensayo | Reset demo. |
| Clic equivocado, estado raro | — | Reset demo (2 segundos) y retomar en el paso 2. |
| El proyector no es 16:9 o corta la pantalla | — | `Ctrl 0` y ajustar el zoom hasta ver los cuatro pagos en una fila. Comprobado hasta 1024×768 (ahí desaparece la columna *Audit chain*; el resto funciona igual). |
| Nada responde | — | Vídeo de 90 segundos. |

## 5. Si vamos tarde

Se recorta en este orden: paso 5 (caducidad) → paso 6 (manipulación, queda para el vídeo) → paso 1 (el verde).

Nunca se recortan los pasos 2 y 3. La versión mínima, de un minuto, es 2 → 3.

## 6. Tres preguntas que la propia demo provoca

- **"That edit button, is it part of the product?"** No. Es un control de demo (`/api/demo/**`) que simula a alguien con acceso directo a la base de datos. En producción no existe. Lo que sí es producto es la detección.
- **"Why can the blocked invoice be paid again?"** Porque el dinero volvió a tesorería. Si se reintenta, el motor la bloquea antes de mover nada: la wallet ya está en la lista de riesgo. Se puede enseñar en directo con *Pay again*.
- **"What stops someone hammering the signature endpoint?"** Tres firmas inválidas cancelan la retención y devuelven el dinero, y el gateway limita las escrituras por origen. `./scripts/attack.sh` lo demuestra: 25 intentos, unos 14 cortados en el perímetro.
