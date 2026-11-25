import { useEffect, useState } from 'react';
import {
  ShieldCheck,
  ShieldAlert,
  Building2,
  KeyRound,
  FileSearch,
  RefreshCw,
  Lock,
  CheckCircle2,
} from 'lucide-react';
import { api, USE_MOCKS } from './services/api';
import TreasuryView from './components/TreasuryView';
import SupplierView from './components/SupplierView';
import AuditView from './components/AuditView';

const formatGBP = (val) =>
  `£${Number(val || 0).toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function App() {
  const [state, setState] = useState(null);
  const [activeTab, setActiveTab] = useState('treasury');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const loadState = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.getState();
      setState(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadState();
  }, []);

  if (loading && !state) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center text-slate-700 font-medium">
        Loading FIS Digital Trust Control Plane...
      </div>
    );
  }

  if (error && !state) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-6">
        <div className="bg-rose-50 border border-rose-200 text-rose-800 p-6 rounded-xl max-w-md">
          <h2 className="font-bold text-lg mb-2">Connection Error</h2>
          <p className="text-sm mb-4">{error}</p>
          <button
            onClick={loadState}
            className="px-4 py-2 bg-rose-700 text-white rounded-lg text-sm font-medium"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  const heldCount = state.payments.filter((p) => p.status === 'HELD').length;

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900 flex flex-col">
      {/* TOP BAR */}
      <header className="bg-slate-900 text-white px-6 py-3 flex items-center justify-between border-b border-slate-800">
        <div className="flex items-center gap-3">
          <ShieldCheck className="w-7 h-7 text-emerald-400" />
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-lg tracking-tight">KeyGuard GBPT</h1>
              <span
                className={`text-xs px-2 py-0.5 rounded font-semibold ${
                  USE_MOCKS ? 'bg-amber-500/20 text-amber-300' : 'bg-emerald-500/20 text-emerald-300'
                }`}
              >
                {USE_MOCKS ? 'MOCK MODE' : 'LIVE API'}
              </span>
            </div>
            <p className="text-xs text-slate-400">
              {state.payer.legalName} ({state.payer.wallet})
            </p>
          </div>
        </div>

        {/* LEDGER SUMMARY */}
        <div className="hidden lg:flex items-center gap-6 text-sm">
          <div>
            <span className="text-slate-400 text-xs block">Treasury Balance</span>
            <span className="font-mono font-bold text-base">{formatGBP(state.ledger.payerBalance)}</span>
          </div>
          <div>
            <span className="text-slate-400 text-xs block">Locked in Escrow</span>
            <span className="font-mono font-bold text-base text-amber-400">
              {formatGBP(state.ledger.inEscrow)}
            </span>
          </div>
          <div>
            <span className="text-slate-400 text-xs block">Protected Fraud</span>
            <span className="font-mono font-bold text-base text-emerald-400">
              {formatGBP(state.metrics.protectedAmount)}
            </span>
          </div>
          <div className="flex items-center gap-1.5 bg-slate-800 px-3 py-1.5 rounded-lg border border-slate-700">
            <span
              className={`w-2 h-2 rounded-full ${
                state.ledger.conserved ? 'bg-emerald-400' : 'bg-rose-500'
              }`}
            />
            <span className="text-xs font-medium">
              Supply: {formatGBP(state.ledger.totalSupply)}
            </span>
          </div>
        </div>

        <button
          onClick={loadState}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-slate-800 hover:bg-slate-700 rounded-lg text-xs font-medium border border-slate-700"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          Refresh
        </button>
      </header>

      {/* NAVIGATION TABS */}
      <nav className="bg-white border-b border-slate-200 px-6 flex items-center gap-2">
        <button
          onClick={() => setActiveTab('treasury')}
          className={`flex items-center gap-2 px-4 py-3 text-sm font-semibold border-b-2 transition ${
            activeTab === 'treasury'
              ? 'border-slate-900 text-slate-900'
              : 'border-transparent text-slate-500 hover:text-slate-800'
          }`}
        >
          <Building2 className="w-4 h-4" />
          Treasury
        </button>
        <button
          onClick={() => setActiveTab('supplier')}
          className={`flex items-center gap-2 px-4 py-3 text-sm font-semibold border-b-2 transition ${
            activeTab === 'supplier'
              ? 'border-slate-900 text-slate-900'
              : 'border-transparent text-slate-500 hover:text-slate-800'
          }`}
        >
          <KeyRound className="w-4 h-4" />
          Supplier Portal
          {heldCount > 0 && (
            <span className="bg-amber-500 text-white text-xs px-1.5 py-0.5 rounded-full">
              {heldCount}
            </span>
          )}
        </button>
        <button
          onClick={() => setActiveTab('audit')}
          className={`flex items-center gap-2 px-4 py-3 text-sm font-semibold border-b-2 transition ${
            activeTab === 'audit'
              ? 'border-slate-900 text-slate-900'
              : 'border-transparent text-slate-500 hover:text-slate-800'
          }`}
        >
          <FileSearch className="w-4 h-4" />
          Compliance & Audit
        </button>
      </nav>

      {/* MAIN WORKSPACE + LIVE AUDIT SIDEBAR */}
      <div className="flex-1 flex overflow-hidden">
        <main className="flex-1 p-6 overflow-y-auto">
          {activeTab === 'treasury' && (
            <TreasuryView
              state={state}
              onRefresh={loadState}
              onMockPaymentCreated={(newPayment) =>
                setState((prev) => ({
                  ...prev,
                  payments: [newPayment, ...prev.payments],
                }))
              }
            />
          )}

          {activeTab === 'supplier' && (
            <SupplierView state={state} onRefresh={loadState} />
          )}

          {activeTab === 'audit' && (
            <AuditView
              state={state}
              onRefresh={loadState}
              onMockTamper={(isValid) =>
                setState((prev) => ({
                  ...prev,
                  audit: { ...prev.audit, chainValid: isValid },
                }))
              }
            />
          )}
        </main>

        {/* RIGHT SIDEBAR: LIVE AUDIT TRAIL */}
        <aside className="w-96 bg-white border-l border-slate-200 flex flex-col">
          <div className="p-4 border-b border-slate-200 flex items-center justify-between bg-slate-50">
            <div className="flex items-center gap-2">
              <Lock className="w-4 h-4 text-slate-700" />
              <h3 className="font-bold text-sm">Live Audit Chain</h3>
            </div>
            <span
              className={`inline-flex items-center gap-1 text-xs font-bold px-2 py-0.5 rounded-full ${
                state.audit.chainValid
                  ? 'bg-emerald-100 text-emerald-800'
                  : 'bg-rose-100 text-rose-800'
              }`}
            >
              {state.audit.chainValid ? (
                <>
                  <CheckCircle2 className="w-3.5 h-3.5" /> VALID ({state.audit.length})
                </>
              ) : (
                <>
                  <ShieldAlert className="w-3.5 h-3.5" /> TAMPERED
                </>
              )}
            </span>
          </div>

          <div className="p-3 bg-slate-900 text-slate-300 font-mono text-[11px] truncate border-b border-slate-800">
            HEAD: {state.audit.headHash}
          </div>

          <div className="flex-1 overflow-y-auto divide-y divide-slate-100">
            {state.audit.tail.map((ev) => (
              <div key={ev.seq} className="p-3 hover:bg-slate-50 text-xs">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono font-bold text-slate-900">
                    #{ev.seq} {ev.eventType}
                  </span>
                  <span className="text-slate-400 font-mono text-[10px]">
                    {ev.occurredAt.slice(11, 19)}
                  </span>
                </div>
                <div className="flex items-center justify-between text-slate-500">
                  <span>{ev.entityRef}</span>
                  <span className="font-mono text-[10px] text-slate-400">
                    {ev.hash.slice(0, 12)}…
                  </span>
                </div>
              </div>
            ))}
          </div>
        </aside>
      </div>
    </div>
  );
}