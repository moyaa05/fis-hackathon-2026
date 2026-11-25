import { useState } from 'react';
import {
  Send,
  ChevronDown,
  ChevronRight,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  Clock,
  ShieldAlert,
  Sparkles,
} from 'lucide-react';
import { api, USE_MOCKS } from '../services/api';

const STATUS_STYLES = {
  SETTLED: 'bg-emerald-100 text-emerald-800 border-emerald-300',
  RELEASED: 'bg-teal-100 text-teal-800 border-teal-300',
  HELD: 'bg-amber-100 text-amber-900 border-amber-300',
  BLOCKED: 'bg-rose-100 text-rose-800 border-rose-300',
  REVERTED: 'bg-slate-200 text-slate-800 border-slate-300',
};

const CHECK_BADGE = {
  PASS: { icon: CheckCircle2, cls: 'text-emerald-700 bg-emerald-50 border-emerald-200' },
  HOLD: { icon: AlertTriangle, cls: 'text-amber-800 bg-amber-50 border-amber-200' },
  BLOCK: { icon: XCircle, cls: 'text-rose-700 bg-rose-50 border-rose-200' },
};

// Presets de 1 clic para la demo (SPEC)
const DEMO_PRESETS = [
  {
    id: 'green',
    label: '1. Routine Payment (Green)',
    sub: 'Brightwater · Registered wallet · £12,400',
    badge: 'APPROVE',
    badgeCls: 'bg-emerald-100 text-emerald-800',
    payload: {
      supplierId: 'BRIGHTWATER',
      payeeWallet: 'tdgb:brightwater:main',
      amount: '12400.00',
      invoiceRef: 'INV-BW-2231',
    },
  },
  {
    id: 'bec',
    label: '2. BEC Fraud Attempt (Amber)',
    sub: 'Acme · Unverified wallet · £80,000',
    badge: 'HOLD / ESCROW',
    badgeCls: 'bg-amber-100 text-amber-900',
    payload: {
      supplierId: 'ACME',
      payeeWallet: 'tdgb:acmetrading:7731',
      amount: '80000.00',
      invoiceRef: 'INV-AC-1147',
    },
  },
  {
    id: 'legit-rot',
    label: '3. Legit Wallet Change (Amber)',
    sub: 'Acme · New bank account · £45,000',
    badge: 'HOLD / ESCROW',
    badgeCls: 'bg-amber-100 text-amber-900',
    payload: {
      supplierId: 'ACME',
      payeeWallet: 'tdgb:acme:op-02',
      amount: '45000.00',
      invoiceRef: 'INV-AC-1152',
    },
  },
  {
    id: 'sanctions',
    label: '4. Sanctioned Wallet (Red)',
    sub: 'Orion · OFSI risk list · £25,000',
    badge: 'BLOCK',
    badgeCls: 'bg-rose-100 text-rose-800',
    payload: {
      supplierId: 'KESTREL',
      payeeWallet: 'tdgb:orion:01',
      amount: '25000.00',
      invoiceRef: 'INV-OR-9901',
    },
  },
];

const formatGBP = (val) =>
  `£${Number(val || 0).toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function TreasuryView({ state, onRefresh, onMockPaymentCreated }) {
  const [expandedId, setExpandedId] = useState(state.payments[0]?.id ?? null);
  const [submitting, setSubmitting] = useState(null);
  const [feedback, setFeedback] = useState(null);

  const handlePresetPay = async (preset) => {
    try {
      setSubmitting(preset.id);
      setFeedback(null);
      const created = await api.createPayment(preset.payload);

      if (USE_MOCKS && onMockPaymentCreated) {
        onMockPaymentCreated({
          ...created,
          id: Date.now(),
          ref: `PAY-MOCK`,
          payeeWallet: preset.payload.payeeWallet,
          amount: preset.payload.amount,
          invoiceRef: preset.payload.invoiceRef,
        });
      } else {
        await onRefresh();
      }

      setExpandedId(created.id);
      setFeedback({
        type: created.status === 'BLOCKED' ? 'error' : created.status === 'HELD' ? 'warn' : 'ok',
        text: `${created.ref || 'Payment'} evaluated: ${created.status} (${created.decision?.outcome || ''})`,
      });
    } catch (err) {
      setFeedback({ type: 'error', text: err.message });
    } finally {
      setSubmitting(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* ONE-CLICK DEMO LAUNCHER */}
      <section className="bg-white rounded-xl border border-slate-200 shadow-sm p-5">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <Sparkles className="w-4 h-4 text-slate-700" />
            <h2 className="text-base font-bold text-slate-900">Quick Payment Scenarios (1-Click Demo)</h2>
          </div>
          <span className="text-xs text-slate-500">
            Executes Risk List → Velocity → Key-Rotation Escrow
          </span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-3">
          {DEMO_PRESETS.map((preset) => (
            <button
              key={preset.id}
              disabled={submitting !== null}
              onClick={() => handlePresetPay(preset)}
              className="text-left p-3.5 rounded-lg border border-slate-200 hover:border-slate-900 hover:bg-slate-50 transition flex flex-col justify-between gap-2 disabled:opacity-50"
            >
              <div>
                <div className="flex items-center justify-between gap-2 mb-1">
                  <span className={`text-[10px] font-bold px-2 py-0.5 rounded ${preset.badgeCls}`}>
                    {preset.badge}
                  </span>
                  <Send className="w-3.5 h-3.5 text-slate-400" />
                </div>
                <div className="font-bold text-sm text-slate-900">{preset.label}</div>
                <div className="text-xs text-slate-500 mt-0.5">{preset.sub}</div>
              </div>
              {submitting === preset.id && (
                <span className="text-xs font-semibold text-slate-700">Evaluating rules...</span>
              )}
            </button>
          ))}
        </div>

        {feedback && (
          <div
            className={`mt-4 px-4 py-2.5 rounded-lg text-sm font-medium border ${
              feedback.type === 'error'
                ? 'bg-rose-50 border-rose-200 text-rose-800'
                : feedback.type === 'warn'
                ? 'bg-amber-50 border-amber-200 text-amber-900'
                : 'bg-emerald-50 border-emerald-200 text-emerald-800'
            }`}
          >
            {feedback.text}
          </div>
        )}
      </section>

      {/* PAYMENTS TABLE WITH EXPLAINABLE RULES */}
      <section>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-bold text-slate-900">Treasury Payments</h2>
          <span className="text-xs text-slate-500">
            Click any row to inspect programmable rule checks
          </span>
        </div>

        <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-200 text-xs uppercase text-slate-500">
                <th className="py-3 px-3 w-8"></th>
                <th className="py-3 px-4">Ref</th>
                <th className="py-3 px-4">Supplier</th>
                <th className="py-3 px-4">Destination Wallet</th>
                <th className="py-3 px-4 text-right">Amount</th>
                <th className="py-3 px-4">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 text-sm">
              {state.payments.map((p) => {
                const isOpen = expandedId === p.id;
                return (
                  <>
                    <tr
                      key={p.id}
                      onClick={() => setExpandedId(isOpen ? null : p.id)}
                      className={`cursor-pointer transition ${
                        isOpen ? 'bg-slate-50' : 'hover:bg-slate-50'
                      }`}
                    >
                      <td className="py-3 px-3 text-slate-400">
                        {isOpen ? (
                          <ChevronDown className="w-4 h-4" />
                        ) : (
                          <ChevronRight className="w-4 h-4" />
                        )}
                      </td>
                      <td className="py-3 px-4 font-mono font-semibold">{p.ref}</td>
                      <td className="py-3 px-4">
                        <div className="font-medium text-slate-900">{p.supplierName}</div>
                        <div className="text-xs text-slate-400">{p.invoiceRef}</div>
                      </td>
                      <td className="py-3 px-4">
                        <div className="font-mono text-xs text-slate-700">{p.payeeWallet}</div>
                        <div className="text-[11px] text-slate-400">
                          Owner: {p.payeeWalletOwner} · {p.payeeWalletStatus}
                        </div>
                      </td>
                      <td className="py-3 px-4 text-right font-mono font-bold">
                        {formatGBP(p.amount)}
                      </td>
                      <td className="py-3 px-4">
                        <span
                          className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-bold border ${
                            STATUS_STYLES[p.status] || 'bg-slate-100'
                          }`}
                        >
                          {p.status}
                        </span>
                      </td>
                    </tr>

                    {isOpen && (
                      <tr key={`${p.id}-details`} className="bg-slate-50/80">
                        <td colSpan={6} className="px-6 py-4 border-t border-slate-200">
                          <div className="space-y-3">
                            <div className="flex items-center justify-between">
                              <span className="text-xs font-bold uppercase tracking-wider text-slate-500">
                                Programmable Rule Evaluation ({p.decision?.outcome})
                              </span>
                              <span className="text-xs font-mono text-slate-400">
                                Evaluated at {p.decision?.evaluatedAt?.slice(11, 19)} UTC
                              </span>
                            </div>

                            <div className="grid grid-cols-1 gap-2">
                              {p.decision?.checks?.map((chk) => {
                                const cfg = CHECK_BADGE[chk.result] || CHECK_BADGE.PASS;
                                const Icon = cfg.icon;
                                return (
                                  <div
                                    key={chk.code}
                                    className={`p-3 rounded-lg border flex items-start gap-3 ${cfg.cls}`}
                                  >
                                    <Icon className="w-4 h-4 mt-0.5 shrink-0" />
                                    <div className="flex-1">
                                      <div className="flex items-center justify-between">
                                        <span className="font-bold text-xs">
                                          {chk.name}{' '}
                                          <span className="font-mono font-normal opacity-75">
                                            ({chk.code} v{chk.ruleVersion})
                                          </span>
                                        </span>
                                        <span className="font-mono text-[11px] font-bold">
                                          {chk.result}
                                        </span>
                                      </div>
                                      <p className="text-xs mt-1 leading-relaxed">{chk.message}</p>
                                    </div>
                                  </div>
                                );
                              })}
                            </div>

                            {p.escrow?.resolution && (
                              <div className="p-3 rounded-lg bg-slate-900 text-slate-100 text-xs flex items-start gap-2.5">
                                <ShieldAlert className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
                                <div>
                                  <span className="font-bold uppercase tracking-wide text-amber-300">
                                    Resolution · {p.escrow.resolution.type}:
                                  </span>{' '}
                                  {p.escrow.resolution.message}
                                </div>
                              </div>
                            )}

                            {p.status === 'HELD' && p.escrow && (
                              <div className="p-3 rounded-lg bg-amber-100/70 border border-amber-300 text-amber-950 text-xs flex items-center gap-2">
                                <Clock className="w-4 h-4 shrink-0" />
                                <span>
                                  <strong>Escrow active:</strong> awaiting Ed25519 signature from{' '}
                                  <code className="font-mono">{p.escrow.trustedWallet}</code>. Go to{' '}
                                  <strong>Supplier Portal</strong> to sign or simulate an attack.
                                </span>
                              </div>
                            )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}