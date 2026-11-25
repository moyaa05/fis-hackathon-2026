import mocks from '../mocks/mocks.json';

// Live API by default. Mock data only with VITE_USE_MOCKS=true (layout work without the backend).
export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS === 'true';

const mock = (key) => structuredClone(mocks[key].body);

// crypto.randomUUID only exists in secure contexts (https or localhost).
const idempotencyKey = () =>
  globalThis.crypto?.randomUUID
    ? globalThis.crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

async function request(path, options = {}) {
  let res;
  try {
    res = await fetch(path, {
      ...options,
      // headers AFTER ...options: otherwise a caller's headers replace Content-Type
      // and the browser sends text/plain (the backend answers 415).
      headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    });
  } catch {
    throw new Error('Cannot reach the gateway. Is the stack running?');
  }

  // nginx answers 502 with HTML while the backend restarts: never assume JSON.
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.detail || data.title || `HTTP ${res.status}`);
    err.status = res.status;
    err.code = data.code;
    throw err;
  }
  return data;
}

const post = (path, body, headers) =>
  request(path, { method: 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body) });

export const api = {
  getState: () => (USE_MOCKS ? Promise.resolve(mock('state')) : request('/api/state')),

  getAudit: (limit = 200) => (USE_MOCKS ? Promise.resolve(mock('audit')) : request(`/api/audit?limit=${limit}`)),

  verifyAudit: () => (USE_MOCKS ? Promise.resolve(mock('auditVerifyValid')) : request('/api/audit/verify')),

  createPayment: (payload) =>
    USE_MOCKS
      ? Promise.resolve(mock('createPaymentHeld'))
      : post('/api/payments', payload, { 'Idempotency-Key': idempotencyKey() }),

  // Supplier side (simulated): someone signs the challenge with a key...
  simSign: (paymentId, as) => {
    if (USE_MOCKS) {
      return Promise.resolve(
        mock(as === 'ATTACKER' ? 'simSignAttacker' : as === 'TAMPERED' ? 'simSignTampered' : 'simSignLegit'),
      );
    }
    return post('/api/sim/sign', { paymentId, as });
  },

  // ...and the bank checks which key produced it. This endpoint is the product.
  authorizeRotation: (paymentId, signature, submittedBy = 'supplier-portal') =>
    USE_MOCKS
      ? Promise.resolve(mock('authorizeReleased'))
      : post(`/api/payments/${paymentId}/rotation-authorization`, { signature, submittedBy }),

  expirePayment: (paymentId) =>
    USE_MOCKS ? Promise.resolve(mock('expireReverted')) : post(`/api/demo/payments/${paymentId}/expire`),

  patchRule: (code, params, changedBy = 'compliance:J.Patel') =>
    USE_MOCKS
      ? Promise.resolve(mock('patchRule'))
      : request(`/api/rules/${code}`, { method: 'PATCH', body: JSON.stringify({ params, changedBy }) }),

  // No seq: the backend picks the latest FUNDS_* event, so it works at any point of the demo.
  tamperAudit: () => (USE_MOCKS ? Promise.resolve(mock('tamper')) : post('/api/demo/tamper')),

  resetDemo: () => (USE_MOCKS ? Promise.resolve(mock('reset')) : post('/api/demo/reset')),
};
