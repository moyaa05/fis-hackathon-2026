import { useState } from 'react';
import { KeyRound, ShieldCheck, ShieldAlert, FilePenLine, CheckCircle2, XCircle, MinusCircle, Clock } from 'lucide-react';
import { api } from '../services/api';
import { gbp, keyPrint, timeLeft, STATUS_STYLE, STATUS_LABEL } from '../lib/format';

const PANEL = {
  RELEASED: 'border-emerald-500 bg-emerald-50',
  BLOCKED: 'border-rose-600 bg-rose-50',
  HELD: 'border-amber-500 bg-amber-50',
};

const SIGNER = {
  LEGIT_SUPPLIER: (v) => `${v.before.supplierName}, with the key of its registered wallet`,
  ATTACKER: () => 'The attacker, with the key of the new wallet',
  TAMPERED: () => 'A registered-key signature over an altered challenge (destination swapped)',
};

export default function SupplierView({ state, verification, onVerification, onSelectPayment, onRefresh }) {
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);

  const walletOf = Object.fromEntries(state.wallets.map((w) => [w.address, w]));
  const held = state.payments.filter((p) => p.status === 'HELD' && p.escrow);
  const resolved = state.payments.filter((p) => p.status !== 'HELD' && p.escrow);

  // Two separate calls on purpose: (1) someone outside the bank signs the challenge,
  // (2) the bank receives only the signature and checks which key produced it.
  const sign = async (payment, actor) => {
    try {
      setBusy(`${payment.id}-${actor}`);
      setError(null);
      const sim = await api.simSign(payment.id, actor);
      const updated = await api.authorizeRotation(payment.id, sim.signature);
      onVerification({ actor, before: payment, sim, updated });
      onSelectPayment(payment.id);
      await onRefresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="space-y-4">
      <div>
        <h2 className="font-bold text-base">Wallet changes waiting for the supplier’s signature</h2>
        <p className="text-xs text-slate-500">
          Simulated counterparty. The bank never sees a private key: it receives a signature and checks which key made it.
        </p>
      </div>

      {error && (
        <div className="px-3 py-2 rounded-lg text-sm font-semibold bg-rose-50 border border-rose-300 text-rose-800">
          {error}
        </div>
      )}

      {verification && <Verification v={verification} />}

      {held.length === 0 && (
        <div className="bg-white p-6 rounded-lg border border-slate-300 text-center text-slate-500">
          Nothing is waiting in escrow. Pay an invoice to a wallet that is not the supplier’s registered one.
        </div>
      )}

      {held.map((p) => {
        const requested = walletOf[p.escrow.requestedWallet];
        const trusted = walletOf[p.escrow.trustedWallet];
        return (
          <section key={p.id} className="bg-white rounded-lg border-2 border-amber-400 shadow-sm">
            <div className="px-4 py-2 bg-amber-50 border-b border-amber-200 rounded-t-lg flex items-center justify-between gap-3">
              <span className="flex items-center gap-2 min-w-0">
                <KeyRound className="w-5 h-5 text-amber-700 shrink-0" />
                <span className="font-bold truncate">
                  {p.ref} · {p.supplierName} is asked to confirm a new wallet
                </span>
              </span>
              <span className="flex items-center gap-3 shrink-0">
                <span className="flex items-center gap-1 text-sm text-amber-900">
                  <Clock className="w-4 h-4" />
                  <span className="font-mono font-bold">{timeLeft(p.escrow.expiresAt, state.serverTime)}</span>
                </span>
                <span className="font-mono font-bold text-xl">{gbp(p.amount)}</span>
              </span>
            </div>

            <div className="p-4 space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div className="p-2.5 rounded-md border border-emerald-300 bg-emerald-50">
                  <span className="block text-xs font-bold uppercase tracking-wide text-emerald-800">
                    Registered wallet · only its key can authorise
                  </span>
                  <span className="block font-mono font-bold text-[15px]">{p.escrow.trustedWallet}</span>
                  <span className="block text-xs text-slate-600">
                    {trusted?.label || p.supplierName} · key{' '}
                    <span className="font-mono font-bold">{keyPrint(p.escrow.trustedPublicKey)}</span>
                  </span>
                </div>
                <div className="p-2.5 rounded-md border border-amber-300 bg-amber-50">
                  <span className="block text-xs font-bold uppercase tracking-wide text-amber-800">
                    Requested destination · not registered
                  </span>
                  <span className="block font-mono font-bold text-[15px]">{p.escrow.requestedWallet}</span>
                  <span className="block text-xs text-slate-600">
                    {p.payeeWalletOwner}
                    {p.payeeKybVerified ? ' · KYB verified' : ''} · key{' '}
                    <span className="font-mono font-bold">{keyPrint(requested?.publicKey)}</span>
                  </span>
                </div>
              </div>

              <div>
                <span className="block text-xs font-bold text-slate-600 mb-1">
                  Challenge to sign · bound to this payment, destination, amount and expiry (a signature cannot be reused)
                </span>
                <pre className="columns-2 gap-6 bg-slate-900 text-emerald-300 px-3 py-2 rounded-md text-xs font-mono leading-relaxed">
                  {p.escrow.challenge}
                </pre>
              </div>

              {p.escrow.attempts?.length > 0 && (
                <p className="text-sm text-rose-800 font-semibold">
                  {p.escrow.attempts.at(-1).message}
                </p>
              )}

              <div className="flex flex-wrap items-center gap-2 pt-1">
                <span className="text-sm font-bold text-slate-600 mr-1">Who signs?</span>
                <button
                  disabled={busy !== null}
                  onClick={() => sign(p, 'LEGIT_SUPPLIER')}
                  className="flex items-center gap-1.5 px-3 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-md text-sm font-bold disabled:opacity-50"
                >
                  <ShieldCheck className="w-4 h-4" />
                  {p.supplierName} · registered key
                </button>
                <button
                  disabled={busy !== null}
                  onClick={() => sign(p, 'ATTACKER')}
                  className="flex items-center gap-1.5 px-3 py-2 bg-rose-600 hover:bg-rose-700 text-white rounded-md text-sm font-bold disabled:opacity-50"
                >
                  <ShieldAlert className="w-4 h-4" />
                  Attacker · key of the new wallet
                </button>
                <button
                  disabled={busy !== null}
                  onClick={() => sign(p, 'TAMPERED')}
                  className="flex items-center gap-1.5 px-3 py-2 border border-slate-400 text-slate-700 hover:bg-slate-100 rounded-md text-sm font-semibold disabled:opacity-50"
                >
                  <FilePenLine className="w-4 h-4" />
                  Altered challenge
                </button>
                {busy?.startsWith(`${p.id}-`) && <span className="text-sm text-slate-500">Verifying…</span>}
              </div>
            </div>
          </section>
        );
      })}

      {resolved.length > 0 && (
        <section>
          <h3 className="font-bold text-sm text-slate-600 mb-1.5">Resolved</h3>
          <ul className="bg-white rounded-lg border border-slate-300 divide-y divide-slate-100">
            {resolved.map((p) => (
              <li key={p.id} className="px-4 py-2 flex items-center justify-between gap-4 text-sm">
                <span className="min-w-0">
                  <span className="font-mono font-bold">{p.ref}</span> · {p.supplierName} ·{' '}
                  <span className="font-mono">{gbp(p.amount)}</span>
                  <span className="block text-slate-600 truncate">{p.escrow.resolution?.message}</span>
                </span>
                <span className={`shrink-0 px-2 py-0.5 rounded text-xs font-bold border ${STATUS_STYLE[p.status] || ''}`}>
                  {STATUS_LABEL[p.status] || p.status}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

// What the bank did with the signature it received: the two questions that decide everything.
function Verification({ v }) {
  const attempt = v.updated.escrow?.attempts?.at(-1);
  const result = attempt?.result; // VALID | SELF_AUTHORISATION | INVALID
  const registeredKey = v.before.escrow.trustedPublicKey;
  const signingKey = v.sim.signerPublicKey;
  const signatureOk = result !== 'INVALID';
  const keyOk = result === 'VALID';

  return (
    <section className={`rounded-lg border-2 ${PANEL[v.updated.status] || PANEL.HELD}`}>
      <div className="px-4 py-2 flex items-center justify-between gap-3 border-b border-black/10">
        <span className="font-bold">
          Bank verification · {v.before.ref}
          <span className="font-normal text-slate-600"> · signed by: {SIGNER[v.actor]?.(v)}</span>
        </span>
        <span className={`shrink-0 px-3 py-1 rounded-md text-sm font-extrabold border ${STATUS_STYLE[v.updated.status] || ''}`}>
          {STATUS_LABEL[v.updated.status] || v.updated.status}
        </span>
      </div>

      <div className="px-4 py-2.5 grid grid-cols-2 gap-x-6 gap-y-1.5 text-sm">
        <Step ok={signatureOk} question="Is it a valid Ed25519 signature of this exact challenge?">
          {signatureOk ? 'Yes — mathematically valid.' : 'No — it does not verify for this challenge.'}
        </Step>
        <Step ok={signatureOk ? keyOk : null} question="Was it made with the supplier’s registered key?">
          {!signatureOk && 'Not reached.'}
          {signatureOk && (
            <>
              registered <span className="font-mono font-bold">{keyPrint(registeredKey)}</span> · signed with{' '}
              <span className="font-mono font-bold">{keyPrint(signingKey)}</span> →{' '}
              <strong>{keyOk ? 'same key' : 'a different key'}</strong>
            </>
          )}
        </Step>
      </div>

      <div className="px-4 pb-3 space-y-1">
        {attempt?.message && <p className="text-base font-bold leading-snug">{attempt.message}</p>}
        {v.updated.escrow?.resolution?.message && (
          <p className="text-[15px] leading-snug">{v.updated.escrow.resolution.message}</p>
        )}
      </div>
    </section>
  );
}

function Step({ ok, question, children }) {
  const Icon = ok === null ? MinusCircle : ok ? CheckCircle2 : XCircle;
  const tone = ok === null ? 'text-slate-400' : ok ? 'text-emerald-600' : 'text-rose-600';
  return (
    <div className="flex items-start gap-2">
      <Icon className={`w-5 h-5 shrink-0 mt-0.5 ${tone}`} />
      <div>
        <span className="block font-bold">{question}</span>
        <span className="block text-slate-700">{children}</span>
      </div>
    </div>
  );
}
