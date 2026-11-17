import { useState, useEffect } from 'react';

export default function App() {
  const [health, setHealth] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const verifyNode = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch('/api/health');
      if (!res.ok) throw new Error(`HTTP ${res.status} - Rate Limited or Down`);
      const data = await res.json();
      setHealth(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    verifyNode();
  }, []);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 p-8 font-sans">
      <header className="flex justify-between items-center border-b border-slate-800 pb-4 mb-8">
        <div>
          <h1 className="text-2xl font-bold">FIS Programmable Engine</h1>
          <p className="text-slate-400 text-sm mt-1">Core Ledger & Policy Control Plane</p>
        </div>
        <button
          onClick={verifyNode}
          disabled={loading}
          className="bg-blue-600 hover:bg-blue-500 text-white px-5 py-2.5 rounded-lg font-semibold transition"
        >
          {loading ? 'Verifying...' : 'Ping Core Engine'}
        </button>
      </header>

      {error && (
        <div className="bg-red-950 border border-red-800 text-red-200 p-4 rounded-lg mb-6">
          <strong>Gateway / Security Alert:</strong> {error}
        </div>
      )}

      {health && (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 space-y-2">
          <h2 className="text-lg font-semibold text-sky-400 mb-3">Backend Telemetry & Cryptographic Proof</h2>
          <p><span className="text-slate-400">Status:</span> <span className="text-emerald-400 font-mono">{health.status}</span></p>
          <p><span className="text-slate-400">Node ID:</span> <span className="font-mono">{health.node}</span></p>
          <p><span className="text-slate-400">Java Virtual Thread:</span> <code className="text-slate-300">{health.thread}</code></p>
          <p><span className="text-slate-400">SHA-256 State Hash:</span> <code className="text-amber-400 break-all">{health.ledgerIntegrityHash}</code></p>
          <p><span className="text-slate-400">Timestamp:</span> <span className="font-mono text-sm">{health.timestamp}</span></p>
        </div>
      )}
    </div>
  );
}
