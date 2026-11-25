import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ShieldCheck,
  ShieldAlert,
  Building2,
  KeyRound,
  Scale,
  RotateCcw,
  WifiOff,
  Link2,
} from 'lucide-react';
import { api, USE_MOCKS } from './services/api';
import { gbp, clock, shortHash, describeEvent } from './lib/format';
import TreasuryView from './components/TreasuryView';
import SupplierView from './components/SupplierView';
import AuditView from './components/AuditView';

const TABS = [
  { id: 'treasury', label: 'Treasury', who: 'Northwind · the payer', icon: Building2 },
  { id: 'supplier', label: 'Supplier side', who: 'outside the bank · simulated', icon: KeyRound },
  { id: 'audit', label: 'Compliance', who: 'rules · audit log · wallets', icon: Scale },
];

export default function App() {
  const [state, setState] = useState(null);
  const [connError, setConnError] = useState(null);
  const [tab, setTab] = useState('treasury');
  const [selectedId, setSelectedId] = useState(null); // payment shown in the decision card
  const [verification, setVerification] = useState(null); // last signature checked on the supplier side
  const [resetStep, setResetStep] = useState('idle'); // idle | armed | running

  // --- state loading: one request at a time, 1 s polling, paused while the tab is hidden ---
  const pending = useRef(null);
  const mainRef = useRef(null);

  const load = useCallback(async () => {
    const data = await api.getState();
    setState(data);
    setConnError(null);
    return data;
  }, []);

  // Used after every command: waits for a poll in flight, then reads again, so the UI never shows a stale snapshot.
  const refresh = useCallback(async () => {
    try {
      if (pending.current) await pending.current.catch(() => {});
      pending.current = load();
      return await pending.current;
    } catch (err) {
      setConnError(err.message);
      return null;
    } finally {
      pending.current = null;
    }
  }, [load]);

  useEffect(() => {
    const tick = () => {
      if (document.hidden || pending.current) return;
      pending.current = load()
        .catch((err) => setConnError(err.message))
        .finally(() => {
          pending.current = null;
        });
    };
    tick();
    const timer = setInterval(tick, 1000);
    document.addEventListener('visibilitychange', tick);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', tick);
    };
  }, [load]);

  // Each tab starts at the top; picking a payment brings its decision card back into view.
  useEffect(() => {
    mainRef.current?.scrollTo({ top: 0 });
  }, [tab]);

  const selectPayment = useCallback((id) => {
    setSelectedId(id);
    mainRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
  }, []);

  // --- reset: two clicks, so a slip of the mouse cannot wipe the demo mid-pitch ---
  useEffect(() => {
    if (resetStep !== 'armed') return undefined;
    const t = setTimeout(() => setResetStep('idle'), 3000);
    return () => clearTimeout(t);
  }, [resetStep]);

  const handleReset = async () => {
    if (resetStep === 'idle') {
      setResetStep('armed');
      return;
    }
    if (resetStep !== 'armed') return;
    setResetStep('running');
    try {
      await api.resetDemo();
      setSelectedId(null);
      setVerification(null);
      setTab('treasury');
      await refresh();
    } catch (err) {
      setConnError(err.message);
    } finally {
      setResetStep('idle');
    }
  };

  if (!state) {
    return (
      <div className="min-h-screen bg-slate-100 flex items-center justify-center p-6">
        <div className="text-center">
          <ShieldCheck className="w-10 h-10 text-slate-400 mx-auto mb-3" />
          <p className="text-slate-700 font-semibold text-lg">
            {connError ? 'Waiting for the gateway…' : 'Loading…'}
          </p>
          {connError && <p className="text-slate-500 text-sm mt-1">{connError}</p>}
        </div>
      </div>
    );
  }

  const { ledger, metrics, audit } = state;
  const chainBroken = audit.chainValid === false;
  const ledgerBroken = ledger.conserved === false;
  const alarm = chainBroken || ledgerBroken;
  const heldCount = state.payments.filter((p) => p.status === 'HELD').length;
  const selected = state.payments.find((p) => p.id === selectedId) || state.payments[0] || null;

  return (
    <div className="h-screen flex flex-col bg-slate-100 text-slate-900 text-[15px] overflow-hidden">
      {/* TOP BAR */}
      <header
        className={`px-5 py-2.5 flex items-center justify-between gap-4 text-white transition-colors ${
          alarm ? 'bg-rose-700' : 'bg-slate-900'
        }`}
      >
        <div className="flex items-center gap-3 min-w-0">
          {alarm ? (
            <ShieldAlert className="w-8 h-8 text-white shrink-0" />
          ) : (
            <ShieldCheck className="w-8 h-8 text-emerald-400 shrink-0" />
          )}
          <div className="min-w-0">
            <h1 className="font-bold text-lg leading-tight tracking-tight">
              KeyGuard GBPT
              {USE_MOCKS && (
                <span className="ml-2 align-middle text-[11px] px-1.5 py-0.5 rounded bg-amber-400 text-slate-900 font-bold">
                  MOCK DATA
                </span>
              )}
            </h1>
            <p className={`text-xs truncate ${alarm ? 'text-rose-100' : 'text-slate-400'}`}>
              Tokenised sterling deposits · prototype on a simulated network
            </p>
          </div>
        </div>

        <div className="flex items-center gap-6">
          <Kpi label="Treasury balance" value={gbp(ledger.payerBalance)} />
          <Kpi label="Locked in escrow" value={gbp(ledger.inEscrow)} tone={alarm ? '' : 'text-amber-300'} />
          <Kpi
            label={`Fraud prevented${metrics.protectedCount ? ` · ${metrics.protectedCount}` : ''}`}
            value={gbp(metrics.protectedAmount)}
            tone={alarm ? '' : 'text-emerald-300'}
          />
          <div
            className={`hidden xl:flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1.5 rounded-lg border ${
              ledgerBroken ? 'bg-white text-rose-700 border-white' : 'border-white/20 text-slate-200'
            }`}
            title="Treasury + suppliers + escrow must always equal the total supply"
          >
            <span className={`w-2 h-2 rounded-full ${ledgerBroken ? 'bg-rose-600' : 'bg-emerald-400'}`} />
            {ledgerBroken ? 'Ledger mismatch' : `Ledger balanced · ${gbp(ledger.totalSupply)}`}
          </div>
        </div>

        <button
          onClick={handleReset}
          disabled={resetStep === 'running'}
          className={`shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border transition ${
            resetStep === 'armed'
              ? 'bg-amber-400 text-slate-900 border-amber-400'
              : 'border-white/25 text-white/80 hover:text-white hover:border-white/60'
          }`}
        >
          <RotateCcw className={`w-3.5 h-3.5 ${resetStep === 'running' ? 'animate-spin' : ''}`} />
          {resetStep === 'armed' ? 'Click again to reset' : resetStep === 'running' ? 'Resetting…' : 'Reset demo'}
        </button>
      </header>

      {/* ALERTS: visible on every tab */}
      {chainBroken && (
        <div className="bg-rose-100 border-b-2 border-rose-600 text-rose-900 px-5 py-2 flex items-center justify-between gap-4">
          <span className="font-bold text-base flex items-center gap-2">
            <ShieldAlert className="w-5 h-5 shrink-0" />
            Audit chain tampering detected at event #{audit.firstInvalidSeq}
            <span className="font-normal text-sm">— the record no longer matches its hash.</span>
          </span>
          {tab !== 'audit' && (
            <button onClick={() => setTab('audit')} className="text-sm font-bold underline shrink-0">
              Open the audit log
            </button>
          )}
        </div>
      )}
      {ledgerBroken && (
        <div className="bg-rose-100 border-b-2 border-rose-600 text-rose-900 px-5 py-2 font-bold">
          Ledger mismatch: treasury + suppliers + escrow no longer equal the total supply.
        </div>
      )}
      {connError && (
        <div className="bg-amber-100 border-b border-amber-400 text-amber-900 px-5 py-1.5 text-sm font-semibold flex items-center gap-2">
          <WifiOff className="w-4 h-4" /> Connection lost — retrying. Showing the last known state. ({connError})
        </div>
      )}

      {/* TABS: one per actor */}
      <nav className="bg-white border-b border-slate-200 px-5 flex items-stretch gap-1">
        {TABS.map(({ id, label, who, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`flex items-center gap-2.5 px-4 py-2 border-b-[3px] transition text-left ${
              tab === id ? 'border-slate-900 text-slate-900' : 'border-transparent text-slate-500 hover:text-slate-800'
            }`}
          >
            <Icon className="w-5 h-5 shrink-0" />
            <span>
              <span className="block font-bold text-[15px] leading-tight">
                {label}
                {id === 'supplier' && heldCount > 0 && (
                  <span className="ml-2 bg-amber-500 text-white text-xs px-1.5 py-0.5 rounded-full align-middle">
                    {heldCount}
                  </span>
                )}
              </span>
              <span className="block text-xs text-slate-500 leading-tight">{who}</span>
            </span>
          </button>
        ))}
      </nav>

      <div className="flex-1 flex min-h-0">
        <main ref={mainRef} className="flex-1 min-w-0 p-4 overflow-y-auto">
          {tab === 'treasury' && (
            <TreasuryView
              state={state}
              selected={selected}
              onSelect={selectPayment}
              onRefresh={refresh}
              onOpenSupplier={() => setTab('supplier')}
            />
          )}
          {tab === 'supplier' && (
            <SupplierView
              state={state}
              verification={verification}
              onVerification={setVerification}
              onSelectPayment={setSelectedId}
              onRefresh={refresh}
            />
          )}
          {tab === 'audit' && <AuditView state={state} onRefresh={refresh} />}
        </main>

        {/* AUDIT CHAIN: always on screen, grows with every action */}
        <aside className="hidden xl:flex w-72 2xl:w-80 shrink-0 bg-white border-l border-slate-200 flex-col min-h-0">
          <div className="px-4 py-2.5 border-b border-slate-200 flex items-center justify-between bg-slate-50">
            <h3 className="font-bold text-sm flex items-center gap-1.5">
              <Link2 className="w-4 h-4" /> Audit chain
            </h3>
            <span
              className={`text-xs font-bold px-2 py-0.5 rounded-full ${
                chainBroken ? 'bg-rose-600 text-white' : 'bg-emerald-100 text-emerald-800'
              }`}
            >
              {chainBroken ? `BROKEN AT #${audit.firstInvalidSeq}` : `VALID · ${audit.length} events`}
            </span>
          </div>
          <div className="px-4 py-1.5 bg-slate-900 text-slate-300 font-mono text-xs truncate" title={audit.headHash}>
            head {shortHash(audit.headHash)}
            <span className="text-slate-500"> · SHA-256 hash chain</span>
          </div>
          <ol className="flex-1 overflow-y-auto">
            {audit.tail.map((ev) => {
              const broken = chainBroken && ev.seq === audit.firstInvalidSeq;
              return (
                <li
                  key={ev.seq}
                  className={`px-4 py-2 border-b border-slate-100 ${broken ? 'bg-rose-100' : ''}`}
                >
                  <div className="flex items-baseline justify-between gap-2">
                    <span className={`font-mono text-xs font-bold ${broken ? 'text-rose-700' : 'text-slate-500'}`}>
                      #{ev.seq}
                    </span>
                    <span className="font-mono text-[11px] text-slate-400">{clock(ev.occurredAt)}</span>
                  </div>
                  <p className={`text-[13px] leading-snug ${broken ? 'text-rose-900 font-semibold' : 'text-slate-800'}`}>
                    {describeEvent(ev)}
                  </p>
                  <p className={`font-mono text-[11px] ${broken ? 'text-rose-700 font-bold' : 'text-slate-400'}`}>
                    {broken ? 'content no longer matches hash ' : ''}
                    {shortHash(ev.hash)}
                  </p>
                </li>
              );
            })}
          </ol>
        </aside>
      </div>
    </div>
  );
}

function Kpi({ label, value, tone = '' }) {
  return (
    <div className="text-right">
      <span className="block text-[11px] uppercase tracking-wide text-white/60 leading-tight">{label}</span>
      <span className={`block font-mono font-bold text-lg leading-tight ${tone}`}>{value}</span>
    </div>
  );
}
