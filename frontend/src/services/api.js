import mocks from '../mocks/mocks.json';

export const USE_MOCKS = true;

async function request(path, options = {}) {
  const res = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
    ...options,
  });

  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.detail || data.title || `HTTP ${res.status}`);
    err.status = res.status;
    err.code = data.code;
    throw err;
  }
  return data;
}

export const api = {
  async getState() {
    if (USE_MOCKS) return structuredClone(mocks.state.body);
    return request('/api/state');
  },

  async getAudit(limit = 200) {
    if (USE_MOCKS) return structuredClone(mocks.audit.body);
    return request(`/api/audit?limit=${limit}`);
  },

  async verifyAudit() {
    if (USE_MOCKS) return structuredClone(mocks.auditVerifyValid.body);
    return request('/api/audit/verify');
  },

  async createPayment(payload) {
    if (USE_MOCKS) return structuredClone(mocks.createPaymentHeld.body);
    return request('/api/payments', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(payload),
    });
  },

  async simSign(paymentId, as) {
    if (USE_MOCKS) {
      if (as === 'ATTACKER') return structuredClone(mocks.simSignAttacker.body);
      if (as === 'TAMPERED') return structuredClone(mocks.simSignTampered.body);
      return structuredClone(mocks.simSignLegit.body);
    }
    return request('/api/sim/sign', {
      method: 'POST',
      body: JSON.stringify({ paymentId, as }),
    });
  },

  async authorizeRotation(paymentId, signature, submittedBy = 'supplier-portal') {
    if (USE_MOCKS) return structuredClone(mocks.authorizeReleased.body);
    return request(`/api/payments/${paymentId}/rotation-authorization`, {
      method: 'POST',
      body: JSON.stringify({ signature, submittedBy }),
    });
  },

  async expirePayment(paymentId) {
    if (USE_MOCKS) return structuredClone(mocks.expireReverted.body);
    return request(`/api/demo/payments/${paymentId}/expire`, { method: 'POST' });
  },

  async patchRule(code, params, changedBy = 'compliance:J.Patel') {
    if (USE_MOCKS) return structuredClone(mocks.patchRule.body);
    return request(`/api/rules/${code}`, {
      method: 'PATCH',
      body: JSON.stringify({ params, changedBy }),
    });
  },

  async tamperAudit(seq = 7) {
    if (USE_MOCKS) return structuredClone(mocks.tamper.body);
    return request('/api/demo/tamper', {
      method: 'POST',
      body: JSON.stringify({ seq }),
    });
  },

  async resetDemo() {
    if (USE_MOCKS) return structuredClone(mocks.reset.body);
    return request('/api/demo/reset', { method: 'POST' });
  },
};