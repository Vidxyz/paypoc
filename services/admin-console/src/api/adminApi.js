import axios from 'axios'

// Use /api prefix which nginx will proxy to payments service
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

const adminApi = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Admin endpoints
export const getAdminPayments = async (page = 0, size = 50, sortBy = 'createdAt', sortDirection = 'DESC') => {
  const response = await adminApi.get('/admin/payments', {
    params: { page, size, sortBy, sortDirection },
  })
  return response.data
}

export const getAdminSellers = async () => {
  const response = await adminApi.get('/admin/sellers')
  return response.data
}

// Refund endpoints (admin-only)
export const createRefund = async (paymentId) => {
  const response = await adminApi.post(`/admin/payments/${paymentId}/refund`)
  return response.data
}

export const getRefundsForPayment = async (paymentId) => {
  const response = await adminApi.get(`/admin/payments/${paymentId}/refunds`)
  return response.data
}

export const getRefund = async (refundId) => {
  const response = await adminApi.get(`/admin/refunds/${refundId}`)
  return response.data
}

// Reconciliation endpoints
export const runReconciliation = async (startDate, endDate, currency = null) => {
  const response = await adminApi.post('/reconciliation/run', {
    startDate,
    endDate,
    currency,
  })
  return response.data
}

export const getReconciliationRuns = async (limit = 10) => {
  const response = await adminApi.get('/reconciliation/runs', {
    params: { limit },
  })
  return response.data
}

export const getReconciliationRun = async (reconciliationId) => {
  const response = await adminApi.get(`/reconciliation/runs/${reconciliationId}`)
  return response.data
}

// Payout endpoints (admin-only)
export const createPayoutForSeller = async (sellerId, currency = 'USD') => {
  const response = await adminApi.post(`/admin/sellers/${sellerId}/payout`, null, {
    params: { currency },
  })
  return response.data
}

export const getPayouts = async (sellerId = null, state = null) => {
  const params = {}
  if (sellerId) params.sellerId = sellerId
  if (state) params.state = state
  const response = await adminApi.get('/admin/payouts', { params })
  return response.data
}

// Chargeback endpoints
export const getChargeback = async (chargebackId) => {
  const response = await adminApi.get(`/chargebacks/${chargebackId}`)
  return response.data
}

export default adminApi

