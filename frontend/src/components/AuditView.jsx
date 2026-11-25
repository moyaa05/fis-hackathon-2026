import { useState } from 'react';
import {
  Sliders,
  ShieldCheck,
  ShieldAlert,
  Skull,
  RotateCcw,
  Save,
  Wallet,
  CheckCircle2,
  AlertTriangle,
} from 'lucide-react';
import { api, USE_MOCKS } from '../services/api';
import mocks from '../mocks/mocks.json';

const WALLET_BADGE = {
  TRUSTED: 'bg-emerald-100 text-emerald-800 border-emerald-300',
  ROTATED: 'bg-slate-200 text-slate-700 border-slate-300',
  UNVERIFIED: 'bg-amber-100 text-amber-900 border-amber-300',
  FLAGGED: 'bg-rose-100 text-rose-800 border-rose-300',
};

const formatGBP = (val) =>
  `£${Number(val || 0).toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function AuditView({ state, onRefresh, onMockTamper }) {
  const velocityRule = state.rules.find((r) => r.code === 'VELOCITY_UNVERIFIED');
  const escrowRule = state.rules.find((r) => r.code === 'NEW_BENEFICIARY_WALLET');

  const [maxAttempts, setMaxAttempts] = useState(velocityRule?.params?.maxAttempts ?? 2);
  const [windowMinutes, setWindowMinutes] = useState(velocityRule?.params?.windowMinutes ?? 30);
  const [holdMinutes, setHoldMinutes] = useState(escrowRule?.params?.holdMinutes ?? 120);

  const [busy, setBusy] = useState(null);
  const [verifyResult, setVerifyResult] = useState(null);
  const [tamperInfo, setTamperInfo] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const [mockIsTampered, setMockIsTampered] = useState(false);

  const handleSaveRule = async (code, params) => {
    try {
      setBusy(code);
      setFeedback(null);
      const updated = await api.patchRule(code, params);
      setFeedback({
        type: 'ok',
        text: `Rule ${updated.code} updated to v${updated.version} by ${updated.updatedBy}.`,
      });
      if (!USE_MOCKS) await onRefresh();
    } catch (err) {
      setFeedback({ type: 'error', text: err.message });
    } finally {
      setBusy(null);
    }
  };

  const handleVerify = async () => {
    try {
      setBusy('verify');
      setFeedback(null);
      const res = USE_MOCKS
        ? structuredClone(mockIsTampered ? mocks.auditVerifyTampered.body : mocks.auditVerifyValid.body)
        : await api.verifyAudit();
      setVerifyResult(res);
      if (!USE_MOCKS) await onRefresh();
    } catch (err) {
      setFeedback({ type: 'error', text: err.message });
    } finally {
      setBusy(null);
    }
  };

  const handleTamper = async () => {
    try {
      setBusy('tamper');
      setFeedback(null);
      const res = await api.tamperAudit(7);
      setTamperInfo(res);
      if (USE_MOCKS) {
        setMockIsTampered(true);
        setVerifyResult(structuredClone(mocks.auditVerifyTampered.body));
        if (onMockTamper) onMockTamper(false);
      } else {
        const verified = await api.verifyAudit();
        setVerifyResult(verified);
        await onRefresh();
      }
    } catch (err) {
      setFeedback({ type: 'error', text: err.message });
    } finally {
      setBusy(null);
    }
  };

  const handleReset = async () => {
    try {
      setBusy('reset');
      setFeedback(null);
      setTamperInfo(null);
      setVerifyResult(null);
      await api.resetDemo();
      if (USE_MOCKS) {
        setMockIsTampered(false);
        if (onMockTamper) onMockTamper(true);
        setFeedback({ type: 'ok', text: 'Demo reset simulated (mock mode).' });
      } else {
        await onRefresh();
        setFeedback({ type: 'ok', text: 'Demo state reset to initial seed.' });
      }
    } catch (err) {
      setFeedback({ type: 'error', text: err.message });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* HEADER + DEMO RESET */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-slate-900">Compliance Rules & Audit Integrity</h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Live programmable policy controls, cryptographic SHA-256 chain verification, and wallet trust registry.
          </p>
        </div>
        <button
          disabled={busy !== null}
          onClick={handleReset}
          className="flex items-center gap-1.5 px-3.5 py-2 bg-slate-900 hover:bg-slate-800 text-white rounded-lg text-xs font-bold disabled:opacity-50"
        >
          <RotateCcw className="w-3.5 h-3.5" />
          Reset Demo State
        </button>
      </div>

      {feedback && (
        <div
          className={`px-4 py-3 rounded-xl text-sm font-medium border ${
            feedback.type === 'error'
              ? 'bg-rose-50 border-rose-200 text-rose-800'
              : 'bg-emerald-50 border-emerald-200 text-emerald-800'
          }`}
        >
          {feedback.text}
        </div>
      )}

      {/* SECTION 1: TAMPER-EVIDENT AUDIT VERIFICATION */}
      <section className="bg-white rounded-xl border border-slate-200 shadow-sm p-5 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            {state.audit.chainValid ? (
              <ShieldCheck className="w-5 h-5 text-emerald-600" />
            ) : (
              <ShieldAlert className="w-5 h-5 text-rose-600" />
            )}
            <h3 className="font-bold text-base text-slate-900">
              Tamper-Evident Audit Chain (SHA-256)
            </h3>
          </div>

          <div className="flex items-center gap-2.5">
            <button
              disabled={busy !== null}
              onClick={handleVerify}
              className="flex items-center gap-1.5 px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-xs font-bold disabled:opacity-50"
            >
              <CheckCircle2 className="w-4 h-4" />
              Verify Hash Chain
            </button>
            <button
              disabled={busy !== null}
              onClick={handleTamper}
              className="flex items-center gap-1.5 px-4 py-2 bg-rose-600 hover:bg-rose-700 text-white rounded-lg text-xs font-bold disabled:opacity-50"
            >
              <Skull className="w-4 h-4" />
              Tamper DB (Demo Attack)
            </button>
          </div>
        </div>

        {tamperInfo && (
          <div className="p-3.5 rounded-lg bg-amber-50 border border-amber-300 text-xs text-amber-950">
            <strong>Direct SQL Attack Executed on seq #{tamperInfo.tamperedSeq}:</strong> changed{' '}
            <code className="font-mono">{tamperInfo.field}</code> from{' '}
            <code className="font-mono font-bold">{tamperInfo.before}</code> to{' '}
            <code className="font-mono font-bold text-rose-700">{tamperInfo.after}</code>.{' '}
            {tamperInfo.note}
          </div>
        )}

        {verifyResult && (
          <div
            className={`p-4 rounded-xl border-2 text-xs space-y-2 ${
              verifyResult.valid
                ? 'bg-emerald-50 border-emerald-300 text-emerald-900'
                : 'bg-rose-50 border-rose-400 text-rose-950'
            }`}
          >
            <div className="flex items-center justify-between">
              <span className="font-bold text-sm flex items-center gap-1.5">
                {verifyResult.valid ? (
                  <>
                    <CheckCircle2 className="w-4 h-4 text-emerald-600" />
                    CHAIN INTEGRITY VERIFIED ({verifyResult.length} events)
                  </>
                ) : (
                  <>
                    <AlertTriangle className="w-4 h-4 text-rose-600" />
                    TAMPERING DETECTED AT SEQ #{verifyResult.firstInvalidSeq} ({verifyResult.reason})
                  </>
                )}
              </span>
              <span className="font-mono text-[11px] opacity-75">
                Checked {verifyResult.checkedAt?.slice(11, 19)} UTC
              </span>
            </div>

            {!verifyResult.valid && (
              <div className="bg-slate-900 text-slate-200 p-3 rounded-lg font-mono text-[11px] space-y-1 overflow-x-auto">
                <div>
                  <span className="text-emerald-400">EXPECTED:</span> {verifyResult.expectedHash}
                </div>
                <div>
                  <span className="text-rose-400">STORED  :</span> {verifyResult.storedHash}
                </div>
              </div>
            )}
          </div>
        )}
      </section>

      {/* SECTION 2: LIVE PROGRAMMABLE RULES EDITOR */}
      <section className="bg-white rounded-xl border border-slate-200 shadow-sm p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Sliders className="w-4 h-4 text-slate-700" />
          <h3 className="font-bold text-base text-slate-900">Programmable Policy Rules</h3>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {state.rules.map((rule) => (
            <div
              key={rule.code}
              className="p-4 rounded-xl border border-slate-200 bg-slate-50 flex flex-col justify-between gap-3"
            >
              <div>
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-[11px] font-bold text-slate-500">
                    P{rule.priority} · {rule.code}
                  </span>
                  <span
                    className={`text-[10px] font-bold px-2 py-0.5 rounded ${
                      rule.outcome === 'BLOCK'
                        ? 'bg-rose-100 text-rose-800'
                        : 'bg-amber-100 text-amber-900'
                    }`}
                  >
                    {rule.outcome} · v{rule.version}
                  </span>
                </div>
                <div className="font-bold text-sm text-slate-900">{rule.name}</div>
                <p className="text-xs text-slate-600 mt-1 leading-relaxed">{rule.description}</p>
              </div>

              {rule.code === 'VELOCITY_UNVERIFIED' && (
                <div className="pt-3 border-t border-slate-200 space-y-2">
                  <div className="grid grid-cols-2 gap-2 text-xs">
                    <label className="block">
                      <span className="text-slate-500 block mb-1">Max Attempts</span>
                      <input
                        type="number"
                        min={1}
                        value={maxAttempts}
                        onChange={(e) => setMaxAttempts(Number(e.target.value))}
                        className="w-full px-2.5 py-1.5 rounded border border-slate-300 bg-white font-mono font-bold"
                      />
                    </label>
                    <label className="block">
                      <span className="text-slate-500 block mb-1">Window (min)</span>
                      <input
                        type="number"
                        min={1}
                        value={windowMinutes}
                        onChange={(e) => setWindowMinutes(Number(e.target.value))}
                        className="w-full px-2.5 py-1.5 rounded border border-slate-300 bg-white font-mono font-bold"
                      />
                    </label>
                  </div>
                  <button
                    disabled={busy !== null}
                    onClick={() =>
                      handleSaveRule('VELOCITY_UNVERIFIED', { maxAttempts, windowMinutes })
                    }
                    className="w-full flex items-center justify-center gap-1.5 py-1.5 bg-slate-900 hover:bg-slate-800 text-white rounded-lg text-xs font-bold disabled:opacity-50"
                  >
                    <Save className="w-3.5 h-3.5" />
                    Update Velocity Rule
                  </button>
                </div>
              )}

              {rule.code === 'NEW_BENEFICIARY_WALLET' && (
                <div className="pt-3 border-t border-slate-200 space-y-2">
                  <label className="block text-xs">
                    <span className="text-slate-500 block mb-1">Escrow Hold (minutes)</span>
                    <input
                      type="number"
                      min={1}
                      value={holdMinutes}
                      onChange={(e) => setHoldMinutes(Number(e.target.value))}
                      className="w-full px-2.5 py-1.5 rounded border border-slate-300 bg-white font-mono font-bold"
                    />
                  </label>
                  <button
                    disabled={busy !== null}
                    onClick={() => handleSaveRule('NEW_BENEFICIARY_WALLET', { holdMinutes })}
                    className="w-full flex items-center justify-center gap-1.5 py-1.5 bg-slate-900 hover:bg-slate-800 text-white rounded-lg text-xs font-bold disabled:opacity-50"
                  >
                    <Save className="w-3.5 h-3.5" />
                    Update Escrow Rule
                  </button>
                </div>
              )}

              {rule.code === 'RISK_LIST' && (
                <div className="pt-3 border-t border-slate-200 text-[11px] text-slate-500">
                  Always enforced · Updated by <span className="font-mono">{rule.updatedBy}</span>
                </div>
              )}
            </div>
          ))}
        </div>
      </section>

      {/* SECTION 3: DIGITAL TRUST REGISTRY (WALLETS) */}
      <section className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-5 py-3.5 border-b border-slate-200 flex items-center justify-between bg-slate-50">
          <div className="flex items-center gap-2">
            <Wallet className="w-4 h-4 text-slate-700" />
            <h3 className="font-bold text-sm text-slate-900">Digital Trust Wallet Registry</h3>
          </div>
          <span className="text-xs text-slate-500">{state.wallets.length} wallets registered</span>
        </div>

        <table className="w-full text-left border-collapse text-xs">
          <thead>
            <tr className="border-b border-slate-200 text-slate-500 uppercase bg-slate-50/50">
              <th className="py-2.5 px-4">Wallet Address</th>
              <th className="py-2.5 px-4">Owner / Bank</th>
              <th className="py-2.5 px-4">Trust Status</th>
              <th className="py-2.5 px-4 text-right">Balance</th>
              <th className="py-2.5 px-4">Notes</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {state.wallets.map((w) => (
              <tr key={w.address} className="hover:bg-slate-50">
                <td className="py-2.5 px-4 font-mono font-semibold text-slate-900">{w.address}</td>
                <td className="py-2.5 px-4">
                  <div className="font-medium text-slate-800">{w.ownerName}</div>
                  <div className="text-[11px] text-slate-400">{w.bank}</div>
                </td>
                <td className="py-2.5 px-4">
                  <span
                    className={`inline-flex px-2 py-0.5 rounded-full text-[10px] font-bold border ${
                      WALLET_BADGE[w.status] || 'bg-slate-100'
                    }`}
                  >
                    {w.status}
                  </span>
                </td>
                <td className="py-2.5 px-4 text-right font-mono font-bold">
                  {formatGBP(w.balance)}
                </td>
                <td className="py-2.5 px-4 text-slate-500">
                  {w.flagReason && <span className="text-rose-700 font-medium">{w.flagReason}</span>}
                  {w.rotatedTo && <span>Rotated → {w.rotatedTo}</span>}
                  {!w.flagReason && !w.rotatedTo && <span>{w.label}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}