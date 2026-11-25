import { useState } from 'react';
import { ArrowRight, CheckCircle2, AlertTriangle, XCircle, MinusCircle, Clock, Send } from 'lucide-react';
import { api } from '../services/api';
import { gbp, clock, timeLeft, STATUS_STYLE, STATUS_LABEL } from '../lib/format';

// The payment run: invoices waiting to be paid, as the treasury team sees them.
// What the control decides is NOT written here: the engine decides it live.
const PAYMENT_RUN = [
  {
    id: 'brightwater',
    supplier: 'Brightwater Logistics',
    note: 'Usual wallet on file',
    payload: { supplierId: 'BRIGHTWATER', payeeWallet: 'tdgb:brightwater:main', amount: '12400.00', invoiceRef: 'INV-BW-2231' },
  },
  {
    id: 'acme-urgent',
    supplier: 'Acme Components',
    note: 'Email: “URGENT: please pay our new trading entity”',
    payload: { supplierId: 'ACME', payeeWallet: 'tdgb:acmetrading:7731', amount: '80000.00', invoiceRef: 'INV-AC-1147' },
  },
  {
    id: 'acme-migration',
    supplier: 'Acme Components',
    note: 'Supplier is moving to a new bank',
    payload: { supplierId: 'ACME', payeeWallet: 'tdgb:acme:op-02', amount: '45000.00', invoiceRef: 'INV-AC-1152' },
  },
  {
    id: 'kestrel-redirect',
    supplier: 'Kestrel Packaging',
    note: 'Invoice asks to pay a third party',
    payload: { supplierId: 'KESTREL', payeeWallet: 'tdgb:orion:01', amount: '25000.00', invoiceRef: 'INV-KP-0912' },
  },
];

const CHECK_STYLE = {
  PASS: { icon: CheckCircle2, row: 'text-slate-700', badge: 'bg-emerald-100 text-emerald-800', iconCls: 'text-emerald-600' },
  HOLD: { icon: AlertTriangle, row: 'text-amber-950 bg-amber-50', badge: 'bg-amber-200 text-amber-900', iconCls: 'text-amber-600' },
  BLOCK: { icon: XCircle, row: 'text-rose-950 bg-rose-50', badge: 'bg-rose-200 text-rose-900', iconCls: 'text-rose-600' },
  SKIPPED: { icon: MinusCircle, row: 'text-slate-400', badge: 'bg-slate-200 text-slate-600', iconCls: 'text-slate-400' },
};

const BORDER = {
  SETTLED: 'border-l-emerald-500',
  RELEASED: 'border-l-emerald-500',
  HELD: 'border-l-amber-500',
  BLOCKED: 'border-l-rose-600',
  REVERTED: 'border-l-slate-400',
};

const headline = (p) => {
  if (p.escrow?.resolution) return p.escrow.resolution.message;
  if (p.status === 'SETTLED') return 'Paid instantly: the destination is the supplier’s registered wallet. Zero friction.';
  if (p.status === 'HELD')
    return 'The money has left treasury but has not reached the destination. Only the supplier’s registered key can release it.';
  if (p.status === 'BLOCKED') return 'Blocked before any money moved.';
  return '';
};

export default function TreasuryView({ state, selected, onSelect, onRefresh, onOpenSupplier }) {
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);

  const run = async (key, action) => {
    try {
      setBusy(key);
      setError(null);
      await action();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(null);
    }
  };

  const pay = (item) =>
    run(item.id, async () => {
      const created = await api.createPayment(item.payload);
      await onRefresh();
      onSelect(created.id);
    });

  const expire = (p) =>
    run(`expire-${p.id}`, async () => {
      await api.expirePayment(p.id);
      await onRefresh();
    });

  return (
    <div className="space-y-4">
      {/* PAYMENT RUN */}
      <section>
        <div className="flex items-baseline justify-between mb-1.5">
          <h2 className="font-bold text-base">Payment run · invoices to pay</h2>
          <span className="text-xs text-slate-500">Every payment passes the same three programmable rules</span>
        </div>
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5">
          {PAYMENT_RUN.map((item) => (
            <button
              key={item.id}
              disabled={busy !== null}
              onClick={() => pay(item)}
              className="group text-left bg-white rounded-lg border border-slate-300 hover:border-slate-900 hover:shadow px-3 py-2 transition disabled:opacity-50"
            >
              <div className="font-bold text-sm truncate">{item.supplier}</div>
              <div className="font-mono text-xs text-slate-700 truncate">→ {item.payload.payeeWallet}</div>
              <div className="text-xs text-slate-500 leading-snug h-8 overflow-hidden">{item.note}</div>
              <div className="mt-1 flex items-center justify-between">
                <span className="font-mono font-bold text-[15px]">
                  {gbp(item.payload.amount)}
                  <span className="hidden 2xl:inline ml-2 font-normal text-[11px] text-slate-400">{item.payload.invoiceRef}</span>
                </span>
                <span className="flex items-center gap-1 text-xs font-bold text-white bg-slate-900 group-hover:bg-slate-700 px-2.5 py-1 rounded">
                  {busy === item.id ? 'Checking…' : 'Pay'} <Send className="w-3.5 h-3.5" />
                </span>
              </div>
            </button>
          ))}
        </div>
        {error && (
          <div className="mt-2 px-3 py-2 rounded-lg text-sm font-semibold bg-rose-50 border border-rose-300 text-rose-800">
            {error}
          </div>
        )}
      </section>

      {/* DECISION: what the control decided for the selected payment, and why */}
      {selected && (
        <section
          className={`bg-white rounded-lg border border-slate-300 border-l-[6px] ${BORDER[selected.status] || ''} shadow-sm`}
        >
          <div className="px-4 pt-3 pb-2 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3 min-w-0">
              <span className={`shrink-0 px-3 py-1 rounded-md text-sm font-extrabold border ${STATUS_STYLE[selected.status] || ''}`}>
                {STATUS_LABEL[selected.status] || selected.status}
              </span>
              <div className="min-w-0">
                <div className="font-bold text-base truncate">
                  {selected.ref} · {selected.supplierName}
                  <span className="font-normal text-slate-500"> · {selected.invoiceRef}</span>
                </div>
                <div className="text-sm text-slate-600 truncate">
                  to <span className="font-mono">{selected.payeeWallet}</span> · owner {selected.payeeWalletOwner}
                  {selected.payeeKybVerified ? ' · KYB verified' : ' · KYB not verified'}
                </div>
              </div>
            </div>
            <div className="font-mono font-bold text-2xl shrink-0">{gbp(selected.amount)}</div>
          </div>

          <p className="px-4 pb-2 text-[15px] font-semibold text-slate-900">{headline(selected)}</p>

          <ul className="border-t border-slate-200 divide-y divide-slate-100">
            {selected.decision?.checks?.map((chk) => {
              const s = CHECK_STYLE[chk.result] || CHECK_STYLE.PASS;
              const Icon = s.icon;
              return (
                <li key={chk.code} className={`px-4 py-1.5 flex items-start gap-2.5 ${s.row}`}>
                  <Icon className={`w-[18px] h-[18px] mt-0.5 shrink-0 ${s.iconCls}`} />
                  <div className="w-52 shrink-0">
                    <span className="font-bold text-sm">{chk.name}</span>
                    <span className="block font-mono text-[11px] opacity-60 leading-tight">rule v{chk.ruleVersion}</span>
                  </div>
                  <span className={`shrink-0 w-[70px] text-center mt-0.5 px-1.5 py-0.5 rounded text-xs font-bold ${s.badge}`}>
                    {chk.result}
                  </span>
                  <p className="text-sm leading-snug">{chk.message}</p>
                </li>
              );
            })}
          </ul>

          {selected.status === 'HELD' && selected.escrow && (
            <div className="px-4 py-2 bg-amber-100 border-t border-amber-300 rounded-b-lg flex items-center justify-between gap-3">
              <span className="flex items-center gap-2 text-sm text-amber-950">
                <Clock className="w-4 h-4 shrink-0" />
                <span>
                  Waiting for <strong>{selected.supplierName}</strong>’s signature · automatic return in{' '}
                  <strong className="font-mono">{timeLeft(selected.escrow.expiresAt, state.serverTime)}</strong>
                </span>
              </span>
              <span className="flex items-center gap-2 shrink-0">
                <button
                  disabled={busy !== null}
                  onClick={() => expire(selected)}
                  className="px-2.5 py-1.5 rounded-md text-xs font-semibold border border-amber-600 text-amber-900 hover:bg-amber-200 disabled:opacity-50"
                  title="Demo control: jump to the end of the escrow window"
                >
                  {busy === `expire-${selected.id}` ? 'Expiring…' : 'Skip to expiry (demo)'}
                </button>
                <button
                  onClick={onOpenSupplier}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-bold bg-slate-900 text-white hover:bg-slate-700"
                >
                  Supplier side <ArrowRight className="w-4 h-4" />
                </button>
              </span>
            </div>
          )}

          {selected.status !== 'HELD' && selected.escrow?.attempts?.length > 0 && (
            <div className="px-4 py-2 border-t border-slate-200 text-sm text-slate-700">
              <span className="font-bold">Last signature · {selected.escrow.attempts.at(-1).result}:</span>{' '}
              {selected.escrow.attempts.at(-1).message}
            </div>
          )}
        </section>
      )}

      {/* ALL PAYMENTS */}
      <section>
        <h2 className="font-bold text-base mb-1.5">Payments</h2>
        <div className="bg-white rounded-lg border border-slate-300 overflow-hidden">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-200 text-xs uppercase text-slate-500">
                <th className="py-2 px-3">Ref</th>
                <th className="py-2 px-3">Supplier</th>
                <th className="py-2 px-3">Destination wallet</th>
                <th className="py-2 px-3 text-right">Amount</th>
                <th className="py-2 px-3">Status</th>
                <th className="py-2 px-3 text-right">Time</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {state.payments.map((p) => (
                <tr
                  key={p.id}
                  onClick={() => onSelect(p.id)}
                  className={`cursor-pointer ${selected?.id === p.id ? 'bg-slate-100' : 'hover:bg-slate-50'}`}
                >
                  <td className="py-1.5 px-3 font-mono font-bold">{p.ref}</td>
                  <td className="py-1.5 px-3">
                    {p.supplierName} <span className="text-slate-400 text-xs">{p.invoiceRef}</span>
                  </td>
                  <td className="py-1.5 px-3">
                    <span className="font-mono text-[13px]">{p.payeeWallet}</span>
                    <span className="text-slate-400 text-xs"> · {p.payeeWalletOwner}</span>
                  </td>
                  <td className="py-1.5 px-3 text-right font-mono font-bold">{gbp(p.amount)}</td>
                  <td className="py-1.5 px-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-bold border ${STATUS_STYLE[p.status] || ''}`}>
                      {STATUS_LABEL[p.status] || p.status}
                    </span>
                  </td>
                  <td className="py-1.5 px-3 text-right font-mono text-xs text-slate-500">{clock(p.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
