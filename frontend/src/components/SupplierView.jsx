import { useState } from 'react';
import {
  KeyRound,
  ShieldCheck,
  ShieldAlert,
  Clock,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  Terminal,
} from 'lucide-react';
import { api, USE_MOCKS } from '../services/api';

const formatGBP = (val) =>
  `£${Number(val || 0).toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function SupplierView({ state, onRefresh }) {
  const [busyKey, setBusyKey] = useState(null);
  const [simResult, setSimResult] = useState(null);
  const [error, setError] = useState(null);

  const heldPayments = state.payments.filter((p) => p.status === 'HELD');
  const resolvedEscrows = state.payments.filter(
    (p) => p.status !== 'HELD' && p.escrow !== null
  );

  const handleSimulateAndSubmit = async (payment, actor) => {
    try {
      setBusyKey(`${payment.id}-${actor}`);
      setError(null);
      setSimResult(null);

      // 1. Generar firma Ed25519 con el simulador
      const signed = await api.simSign(payment.id, actor);

      // 2. Enviar autorización al motor de rotación
      const updated = await api.authorizeRotation(payment.id, signed.signature);

      setSimResult({
        actor,
        paymentRef: payment.ref,
        signerWallet: signed.signerWallet,
        signature: signed.signature,
        explanation: signed.explanation,
        newStatus: updated.status,
        resolution: updated.escrow?.resolution?.message || updated.escrow?.attempts?.slice(-1)[0]?.message,
      });

      if (!USE_MOCKS) {
        await onRefresh();
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyKey(null);
    }
  };

  const handleExpire = async (payment) => {
    try {
      setBusyKey(`${payment.id}-EXPIRE`);
      setError(null);
      setSimResult(null);

      const updated = await api.expirePayment(payment.id);
      setSimResult({
        actor: 'EXPIRE',
        paymentRef: payment.ref,
        newStatus: updated.status,
        resolution: updated.escrow?.resolution?.message || 'Escrow expired and funds returned.',
      });

      if (!USE_MOCKS) {
        await onRefresh();
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyKey(null);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-slate-900">Supplier Key-Rotation Portal</h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Payments to unverified wallets remain locked in programmable escrow until signed with the supplier&apos;s registered Ed25519 key.
          </p>
        </div>
        <span className="bg-amber-100 text-amber-900 border border-amber-300 px-3 py-1 rounded-full text-xs font-bold">
          {heldPayments.length} Pending Escrow
        </span>
      </div>

      {/* FEEDBACK DEL SIMULADOR */}
      {error && (
        <div className="p-4 rounded-xl bg-rose-50 border border-rose-200 text-rose-800 text-sm font-medium">
          {error}
        </div>
      )}

      {simResult && (
        <div className="p-4 rounded-xl bg-slate-900 text-slate-100 border border-slate-700 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-amber-400">
              Crypto Verification Result · {simResult.paymentRef} ({simResult.actor})
            </span>
            <span className="text-xs font-mono font-bold px-2 py-0.5 rounded bg-slate-800 text-white">
              Status → {simResult.newStatus}
            </span>
          </div>
          {simResult.explanation && (
            <p className="text-xs text-slate-300">{simResult.explanation}</p>
          )}
          {simResult.resolution && (
            <p className="text-sm font-semibold text-white">{simResult.resolution}</p>
          )}
          {simResult.signature && (
            <div className="text-[11px] font-mono text-slate-400 truncate bg-slate-950 p-2 rounded">
              Ed25519 Sig ({simResult.signerWallet}): {simResult.signature}
            </div>
          )}
        </div>
      )}

      {/* ESCROWS PENDIENTES */}
      {heldPayments.length === 0 ? (
        <div className="bg-white p-8 rounded-xl border border-slate-200 text-center text-slate-500 text-sm">
          No payments currently held in escrow. Trigger a payment to an unverified wallet from Treasury.
        </div>
      ) : (
        <div className="space-y-4">
          {heldPayments.map((p) => (
            <div
              key={p.id}
              className="bg-white rounded-xl border-2 border-amber-300 shadow-sm overflow-hidden"
            >
              <div className="bg-amber-50 px-5 py-3 border-b border-amber-200 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <KeyRound className="w-5 h-5 text-amber-700" />
                  <div>
                    <span className="font-mono font-bold text-slate-900">{p.ref}</span>
                    <span className="mx-2 text-slate-400">·</span>
                    <span className="font-semibold text-slate-800">{p.supplierName}</span>
                  </div>
                </div>
                <div className="text-right">
                  <span className="font-mono font-bold text-lg text-slate-900">
                    {formatGBP(p.amount)}
                  </span>
                  <span className="block text-[11px] text-amber-800 font-medium">
                    Auto-return at {p.escrow?.expiresAt?.slice(11, 16)} UTC
                  </span>
                </div>
              </div>

              <div className="p-5 space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                  <div className="p-3 rounded-lg bg-slate-50 border border-slate-200">
                    <span className="text-slate-500 block mb-1">Registered Trusted Wallet (Must Sign)</span>
                    <code className="font-mono font-bold text-emerald-700 text-sm">
                      {p.escrow?.trustedWallet}
                    </code>
                  </div>
                  <div className="p-3 rounded-lg bg-slate-50 border border-slate-200">
                    <span className="text-slate-500 block mb-1">Requested New Destination</span>
                    <code className="font-mono font-bold text-amber-800 text-sm">
                      {p.escrow?.requestedWallet}
                    </code>
                  </div>
                </div>

                {/* CHALLENGE */}
                <div>
                  <div className="flex items-center gap-1.5 text-xs font-bold text-slate-600 mb-1.5">
                    <Terminal className="w-3.5 h-3.5" />
                    Cryptographic Challenge (Signed Payload)
                  </div>
                  <pre className="bg-slate-900 text-emerald-300 p-3 rounded-lg text-xs font-mono overflow-x-auto leading-relaxed">
                    {p.escrow?.challenge}
                  </pre>
                </div>

                {/* INTENTOS PREVIOS */}
                {p.escrow?.attempts?.length > 0 && (
                  <div className="space-y-1.5">
                    <span className="text-xs font-bold text-slate-600">
                      Previous Verification Attempts ({p.escrow.attempts.length}/{p.escrow.maxInvalidAttempts})
                    </span>
                    {p.escrow.attempts.map((att, idx) => (
                      <div
                        key={idx}
                        className="p-2.5 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-800 flex items-start gap-2"
                      >
                        <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
                        <div>
                          <span className="font-mono font-bold">{att.result}:</span> {att.message}
                        </div>
                      </div>
                    ))}
                  </div>
                )}

                {/* BOTONES DEL SIMULADOR */}
                <div className="pt-2 border-t border-slate-200 flex flex-wrap items-center gap-2.5">
                  <button
                    disabled={busyKey !== null}
                    onClick={() => handleSimulateAndSubmit(p, 'LEGIT_SUPPLIER')}
                    className="flex items-center gap-1.5 px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-xs font-bold disabled:opacity-50"
                  >
                    <ShieldCheck className="w-4 h-4" />
                    Sign as Legit Supplier (Release & Rotate)
                  </button>

                  <button
                    disabled={busyKey !== null}
                    onClick={() => handleSimulateAndSubmit(p, 'ATTACKER')}
                    className="flex items-center gap-1.5 px-4 py-2 bg-rose-600 hover:bg-rose-700 text-white rounded-lg text-xs font-bold disabled:opacity-50"
                  >
                    <ShieldAlert className="w-4 h-4" />
                    Sign as Attacker (Self-Auth Trap)
                  </button>

                  <button
                    disabled={busyKey !== null}
                    onClick={() => handleSimulateAndSubmit(p, 'TAMPERED')}
                    className="flex items-center gap-1.5 px-4 py-2 bg-amber-600 hover:bg-amber-700 text-white rounded-lg text-xs font-bold disabled:opacity-50"
                  >
                    <XCircle className="w-4 h-4" />
                    Submit Tampered Signature
                  </button>

                  <button
                    disabled={busyKey !== null}
                    onClick={() => handleExpire(p)}
                    className="ml-auto flex items-center gap-1.5 px-3 py-2 bg-slate-200 hover:bg-slate-300 text-slate-800 rounded-lg text-xs font-bold disabled:opacity-50"
                  >
                    <Clock className="w-4 h-4" />
                    Simulate Expiry
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* HISTORIAL DE ESCROWS RESUELTOS */}
      {resolvedEscrows.length > 0 && (
        <section className="pt-2">
          <h3 className="text-sm font-bold text-slate-700 mb-2">Resolved Escrows</h3>
          <div className="bg-white rounded-xl border border-slate-200 divide-y divide-slate-100">
            {resolvedEscrows.map((p) => (
              <div key={p.id} className="p-4 flex items-start justify-between gap-4 text-xs">
                <div>
                  <div className="flex items-center gap-2 mb-1">
                    <span className="font-mono font-bold text-slate-900">{p.ref}</span>
                    <span className="font-semibold text-slate-700">{p.supplierName}</span>
                    <span className="font-mono text-slate-500">{formatGBP(p.amount)}</span>
                  </div>
                  <p className="text-slate-600">{p.escrow?.resolution?.message}</p>
                </div>
                <span className="font-mono font-bold px-2 py-0.5 rounded bg-slate-100 text-slate-800 shrink-0">
                  {p.status}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}