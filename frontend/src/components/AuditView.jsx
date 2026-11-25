import { useEffect, useState } from 'react';
import { ShieldCheck, ShieldAlert, DatabaseZap, Sliders, Save, Wallet, ScrollText } from 'lucide-react';
import { api } from '../services/api';
import { gbp, clock, keyPrint, shortHash, payRef } from '../lib/format';

const WALLET_BADGE = {
  TRUSTED: 'bg-emerald-100 text-emerald-800 border-emerald-300',
  ROTATED: 'bg-slate-200 text-slate-700 border-slate-300',
  UNVERIFIED: 'bg-amber-100 text-amber-900 border-amber-300',
  FLAGGED: 'bg-rose-100 text-rose-800 border-rose-300',
};

const WALLET_LABEL = { TRUSTED: 'REGISTERED', ROTATED: 'RETIRED', UNVERIFIED: 'NOT REGISTERED', FLAGGED: 'RISK LIST' };

const RULE_FIELDS = {
  VELOCITY_UNVERIFIED: [
    { key: 'maxAttempts', label: 'Max attempts' },
    { key: 'windowMinutes', label: 'Window (min)' },
  ],
  NEW_BENEFICIARY_WALLET: [
    { key: 'holdMinutes', label: 'Escrow window (min)' },
    { key: 'maxInvalidAttempts', label: 'Invalid signatures allowed' },
  ],
};

const compactPayload = (payload) =>
  Object.entries(payload || {})
    .map(([k, v]) => {
      const text = typeof v === 'object' ? JSON.stringify(v) : String(v);
      return `${k}: ${text.length > 44 ? `${text.slice(0, 20)}…` : text}`;
    })
    .join(' · ');

export default function AuditView({ state, onRefresh }) {
  const { audit } = state;
  const [log, setLog] = useState([]);
  const [report, setReport] = useState(null);
  const [tamper, setTamper] = useState(null);
  const [busy, setBusy] = useState(null);
  const [feedback, setFeedback] = useState(null);

  // The log and the verification report follow the chain: reload whenever it grows or its validity changes.
  useEffect(() => {
    let cancelled = false;
    Promise.all([api.getAudit(200), api.verifyAudit()])
      .then(([list, verify]) => {
        if (cancelled) return;
        setLog(list.events || []);
        setReport(verify);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [audit.length, audit.headHash, audit.chainValid, audit.firstInvalidSeq, tamper]);

  const handleTamper = async () => {
    try {
      setBusy('tamper');
      setFeedback(null);
      setTamper(await api.tamperAudit());
      await onRefresh();
    } catch (err) {
      setFeedback({ ok: false, text: err.message });
    } finally {
      setBusy(null);
    }
  };

  const handleSaveRule = async (code, params) => {
    try {
      setBusy(code);
      setFeedback(null);
      const updated = await api.patchRule(code, params);
      setFeedback({ ok: true, text: `${updated.name} is now v${updated.version}, changed by ${updated.updatedBy}. The change is in the audit log.` });
      await onRefresh();
    } catch (err) {
      setFeedback({ ok: false, text: err.message });
    } finally {
      setBusy(null);
    }
  };

  const broken = audit.chainValid === false;

  return (
    <div className="space-y-4">
      {/* 1 · INTEGRITY */}
      <section className={`rounded-lg border-2 bg-white ${broken ? 'border-rose-600' : 'border-emerald-500'}`}>
        <div className="px-4 py-2.5 flex items-center justify-between gap-4">
          <div className="flex items-center gap-2.5 min-w-0">
            {broken ? (
              <ShieldAlert className="w-6 h-6 text-rose-600 shrink-0" />
            ) : (
              <ShieldCheck className="w-6 h-6 text-emerald-600 shrink-0" />
            )}
            <div className="min-w-0">
              <h2 className="font-bold text-base">
                {broken
                  ? `Tampering detected at event #${audit.firstInvalidSeq}`
                  : `Audit chain verified · ${audit.length} events`}
              </h2>
              <p className="text-sm text-slate-600">
                Tamper-evident: each event’s SHA-256 hash includes the previous one. The whole chain is re-verified on
                every refresh.
              </p>
            </div>
          </div>
          <button
            disabled={busy !== null}
            onClick={handleTamper}
            className="shrink-0 flex items-center gap-1.5 px-3 py-2 border border-rose-400 text-rose-700 hover:bg-rose-50 rounded-md text-sm font-bold disabled:opacity-50"
            title="Demo control: writes to the database directly, bypassing the application"
          >
            <DatabaseZap className="w-4 h-4" />
            Edit a record directly in the database (demo)
          </button>
        </div>

        {tamper && broken && (
          <p className="px-4 py-2 border-t border-amber-300 bg-amber-50 text-sm text-amber-950">
            Direct database write on event <strong>#{tamper.tamperedSeq}</strong>: <span className="font-mono">{tamper.field}</span>{' '}
            changed from <strong className="font-mono">{gbp(tamper.before)}</strong> to{' '}
            <strong className="font-mono text-rose-700">{gbp(tamper.after)}</strong>. The stored hash was not touched.
          </p>
        )}

        {broken && report && report.valid === false && (
          <div className="px-4 py-2 border-t border-rose-300 bg-slate-900 text-slate-200 font-mono text-xs rounded-b-md space-y-0.5 overflow-x-auto">
            <div>
              <span className="text-rose-400">{report.reason}</span> at event #{report.firstInvalidSeq}
            </div>
            <div>
              <span className="text-emerald-400">hash of the record as it is now </span> {report.expectedHash}
            </div>
            <div>
              <span className="text-rose-400">hash stored when it was written </span> {report.storedHash}
            </div>
          </div>
        )}
      </section>

      {feedback && (
        <div
          className={`px-3 py-2 rounded-lg text-sm font-semibold border ${
            feedback.ok ? 'bg-emerald-50 border-emerald-300 text-emerald-800' : 'bg-rose-50 border-rose-300 text-rose-800'
          }`}
        >
          {feedback.text}
        </div>
      )}

      {/* 2 · LOG */}
      <section>
        <h2 className="font-bold text-base mb-1.5 flex items-center gap-2">
          <ScrollText className="w-4 h-4" /> Audit log
          <span className="font-normal text-xs text-slate-500">newest first</span>
        </h2>
        <div className="bg-white rounded-lg border border-slate-300 max-h-64 overflow-y-auto">
          <table className="w-full text-left text-[13px]">
            <thead className="sticky top-0 bg-slate-50">
              <tr className="border-b border-slate-200 text-xs uppercase text-slate-500">
                <th className="py-1.5 px-3">#</th>
                <th className="py-1.5 px-3">Time</th>
                <th className="py-1.5 px-3">Event</th>
                <th className="py-1.5 px-3">Actor</th>
                <th className="py-1.5 px-3">Recorded data</th>
                <th className="py-1.5 px-3">Hash</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {log.map((ev) => {
                const bad = broken && ev.seq === audit.firstInvalidSeq;
                return (
                  <tr key={ev.seq} className={bad ? 'bg-rose-100 text-rose-950 font-semibold' : ''}>
                    <td className="py-1 px-3 font-mono font-bold">{ev.seq}</td>
                    <td className="py-1 px-3 font-mono text-xs whitespace-nowrap">{clock(ev.occurredAt)}</td>
                    <td className="py-1 px-3 whitespace-nowrap">
                      <span className="font-mono text-xs font-bold">{ev.eventType}</span>
                      <span className="text-slate-500 text-xs"> {payRef(ev.entityRef)}</span>
                    </td>
                    <td className="py-1 px-3 text-xs whitespace-nowrap">{ev.actor}</td>
                    <td className="py-1 px-3 text-xs font-mono break-words">{compactPayload(ev.payload)}</td>
                    <td className="py-1 px-3 font-mono text-xs whitespace-nowrap">
                      {bad ? 'MISMATCH' : shortHash(ev.hash)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>

      {/* 3 · RULES */}
      <section>
        <h2 className="font-bold text-base mb-1.5 flex items-center gap-2">
          <Sliders className="w-4 h-4" /> Programmable rules
          <span className="font-normal text-xs text-slate-500">
            edited live · every change is versioned and written to the audit chain
          </span>
        </h2>
        <div className="grid grid-cols-1 xl:grid-cols-3 gap-3">
          {state.rules.map((rule) => (
            <RuleCard
              key={`${rule.code}-${rule.version}-${rule.updatedAt}`}
              rule={rule}
              busy={busy !== null}
              onSave={handleSaveRule}
            />
          ))}
        </div>
      </section>

      {/* 4 · WALLETS */}
      <section>
        <h2 className="font-bold text-base mb-1.5 flex items-center gap-2">
          <Wallet className="w-4 h-4" /> Wallet registry
          <span className="font-normal text-xs text-slate-500">
            every wallet has a key — that is what lets a supplier sign, and what a bank account cannot do
          </span>
        </h2>
        <div className="bg-white rounded-lg border border-slate-300 overflow-hidden">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-200 text-xs uppercase text-slate-500">
                <th className="py-1.5 px-3">Wallet</th>
                <th className="py-1.5 px-3">Owner · bank</th>
                <th className="py-1.5 px-3">Public key</th>
                <th className="py-1.5 px-3">Status</th>
                <th className="py-1.5 px-3 text-right">Balance</th>
                <th className="py-1.5 px-3">Notes</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {state.wallets.map((w) => (
                <tr key={w.address}>
                  <td className="py-1.5 px-3 font-mono font-bold text-[13px] whitespace-nowrap">{w.address}</td>
                  <td className="py-1.5 px-3 leading-tight">
                    {w.ownerName}
                    <span className="block text-xs text-slate-400">{w.bank}</span>
                  </td>
                  <td className="py-1.5 px-3 font-mono text-xs whitespace-nowrap">{keyPrint(w.publicKey)}</td>
                  <td className="py-1.5 px-3">
                    <span className={`inline-block whitespace-nowrap px-2 py-0.5 rounded text-xs font-bold border ${WALLET_BADGE[w.status] || ''}`}>
                      {WALLET_LABEL[w.status] || w.status}
                    </span>
                  </td>
                  <td className="py-1.5 px-3 text-right font-mono font-bold">{gbp(w.balance)}</td>
                  <td className="py-1.5 px-3 text-xs text-slate-600">
                    {w.flagReason ? (
                      <span className="text-rose-700 font-semibold">{w.flagReason}</span>
                    ) : w.rotatedTo ? (
                      `Replaced by ${w.rotatedTo}`
                    ) : w.rotatedFrom ? (
                      `Replaces ${w.rotatedFrom}`
                    ) : (
                      w.label
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

// Remounted (via key) whenever the rule changes on the server, so the inputs never show stale values after a reset.
function RuleCard({ rule, busy, onSave }) {
  const fields = RULE_FIELDS[rule.code] || [];
  const [values, setValues] = useState(() => Object.fromEntries(fields.map((f) => [f.key, rule.params?.[f.key] ?? ''])));
  const changed = fields.some((f) => String(values[f.key]) !== String(rule.params?.[f.key]));

  return (
    <div className="bg-white p-3 rounded-lg border border-slate-300 flex flex-col gap-2">
      <div>
        <div className="flex items-center justify-between gap-2">
          <span className="font-bold text-sm">
            {rule.priority}. {rule.name}
          </span>
          <span
            className={`text-xs font-bold px-2 py-0.5 rounded ${
              rule.outcome === 'BLOCK' ? 'bg-rose-100 text-rose-800' : 'bg-amber-100 text-amber-900'
            }`}
          >
            {rule.outcome}
          </span>
        </div>
        <p className="text-[13px] text-slate-600 leading-snug mt-0.5">{rule.description}</p>
      </div>

      {fields.length > 0 && (
        <div className="flex items-end gap-2">
          {fields.map((f) => (
            <label key={f.key} className="flex-1 text-xs text-slate-500">
              {f.label}
              <input
                type="number"
                min={1}
                value={values[f.key]}
                onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                className="mt-0.5 w-full px-2 py-1 rounded border border-slate-300 font-mono font-bold text-sm text-slate-900"
              />
            </label>
          ))}
          <button
            disabled={busy || !changed}
            onClick={() => onSave(rule.code, Object.fromEntries(fields.map((f) => [f.key, Number(values[f.key])])))}
            className="flex items-center gap-1 px-3 py-1.5 bg-slate-900 hover:bg-slate-700 text-white rounded text-xs font-bold disabled:opacity-40"
          >
            <Save className="w-3.5 h-3.5" /> Save
          </button>
        </div>
      )}

      <div className="text-xs text-slate-500 font-mono mt-auto">
        v{rule.version} · {rule.updatedBy} · {clock(rule.updatedAt)}
      </div>
    </div>
  );
}
