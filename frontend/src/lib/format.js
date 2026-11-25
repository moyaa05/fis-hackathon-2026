// Formatting helpers shared by the three views.
// GBPT is not an ISO 4217 code (Intl throws RangeError on it): format as GBP and label it "tokenised £".

const GBP = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' });

export const gbp = (value) => GBP.format(Number(value || 0));

export const clock = (iso) =>
  iso
    ? new Date(iso).toLocaleTimeString('en-GB', { timeZone: 'Europe/London', hour12: false })
    : '';

export const payRef = (entityRef) => {
  const m = /^payment:(\d+)$/.exec(entityRef || '');
  return m ? `PAY-${m[1].padStart(4, '0')}` : entityRef;
};

// Every Ed25519 public key (SPKI, base64) starts with the same 16 characters.
// The fingerprint shows what actually differs between two keys.
export const keyPrint = (spkiB64) =>
  spkiB64 && spkiB64.length > 28 ? `${spkiB64.slice(16, 22)}…${spkiB64.slice(-7)}` : '—';

export const shortHash = (hex) => (hex ? `${hex.slice(0, 10)}…` : '');

// Time left in an escrow, measured on the SERVER clock (state.serverTime), never the laptop's.
export const timeLeft = (expiresAt, serverTime) => {
  if (!expiresAt || !serverTime) return '';
  const ms = Date.parse(expiresAt) - Date.parse(serverTime);
  if (ms <= 0) return 'expiring…';
  const s = Math.floor(ms / 1000);
  const pad = (n) => String(n).padStart(2, '0');
  return `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
};

export const STATUS_STYLE = {
  SETTLED: 'bg-emerald-100 text-emerald-900 border-emerald-300',
  RELEASED: 'bg-emerald-100 text-emerald-900 border-emerald-300',
  HELD: 'bg-amber-100 text-amber-900 border-amber-400',
  BLOCKED: 'bg-rose-100 text-rose-900 border-rose-300',
  REVERTED: 'bg-slate-200 text-slate-800 border-slate-300',
};

export const STATUS_LABEL = {
  SETTLED: 'SETTLED',
  RELEASED: 'RELEASED',
  HELD: 'HELD IN ESCROW',
  BLOCKED: 'BLOCKED',
  REVERTED: 'RETURNED',
};

// One plain-English line per audit event, built from the event's own payload.
export const describeEvent = (ev) => {
  const p = ev.payload || {};
  const ref = payRef(ev.entityRef);
  switch (ev.eventType) {
    case 'DEMO_RESET':
      return `Ledger seeded · ${gbp(p.totalSupply)} in treasury`;
    case 'PAYMENT_DECIDED':
      return `${ref} decided: ${p.outcome} · ${gbp(p.amount)}`;
    case 'FUNDS_SETTLED':
      return `${ref} · ${gbp(p.amount)} settled`;
    case 'FUNDS_HELD':
      return `${ref} · ${gbp(p.amount)} locked in escrow`;
    case 'ROTATION_SIGNATURE_VERIFIED':
      return `${ref} · signature verified with the registered key`;
    case 'ROTATION_SIGNATURE_REJECTED':
      return `${ref} · signature rejected (${String(p.result || '').replace('_', ' ').toLowerCase()})`;
    case 'FUNDS_RELEASED':
      return `${ref} · ${gbp(p.amount)} released to supplier`;
    case 'FUNDS_RETURNED':
      return `${ref} · ${gbp(p.amount)} returned to treasury`;
    case 'ESCROW_EXPIRED':
      return `${ref} · escrow window expired`;
    case 'WALLET_ROTATED':
      return `${p.supplierId}: registered wallet is now ${p.to}`;
    case 'WALLET_FLAGGED':
      return `${p.wallet} added to the risk list`;
    case 'RULE_UPDATED':
      return `${p.rule} changed: v${p.before?.version} → v${p.after?.version}`;
    default:
      return `${ev.eventType} · ${ev.entityRef}`;
  }
};
