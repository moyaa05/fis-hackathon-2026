# KeyGuard GBPT · frontend

React 19 + Vite + Tailwind CSS v3. One screen, three tabs (one per actor) and an always-visible audit chain.

| Tab | Who is acting | What it shows |
|---|---|---|
| Treasury | Northwind, the payer | Payment run, and for each payment the decision with the three rule checks |
| Supplier side | The counterparty, outside the bank (simulated) | The challenge to sign, who signs it, and how the bank verifies the signature |
| Compliance | The bank | Audit chain integrity, full audit log, programmable rules, wallet registry |

## Run

The demo is always opened through the gateway: **http://localhost:18080** (from the repo root: `docker compose up -d --build`).

For development with hot reload, with the stack already running:

```bash
npm install
npm run dev        # http://localhost:5173 — /api is proxied to the backend on :8081
```

`VITE_USE_MOCKS=true npm run dev` renders canned data from `src/mocks/mocks.json` without a backend.

## Where things are

- `src/services/api.js` — every call to the API. Relative `/api/...` paths only.
- `src/App.jsx` — 1 s polling of `GET /api/state`, top bar, alerts, tabs, audit chain. Keys `1` `2` `3` switch tabs.
- `src/components/` — one file per tab.
- `src/lib/format.js` — money (`en-GB`, GBP), times (`Europe/London`), key fingerprints, audit event summaries.

Presenting: Chrome full screen (F11) at 150 % zoom on a 1080p display.
