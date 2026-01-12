import axios from 'axios'

// Call payments service directly (CORS is enabled on payments service)
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'https://payments.local'

const adminApi = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor to include bearer token
adminApi.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('bearerToken')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

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

// Refund endpoint (reuse from paymentsApi)
export const createRefund = async (paymentId) => {
  const response = await adminApi.post(`/payments/${paymentId}/refund`)
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

// Payout endpoints
export const createPayoutForSeller = async (sellerId, currency = 'USD') => {
  const response = await adminApi.post(`/sellers/${sellerId}/payout`, null, {
    params: { currency },
  })
  return response.data
}

export const getPayouts = async (sellerId = null, state = null) => {
  const params = {}
  if (sellerId) params.sellerId = sellerId
  if (state) params.state = state
  const response = await adminApi.get('/payouts', { params })
  return response.data
}

export default adminApi

