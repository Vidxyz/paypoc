import axios from 'axios'

// Call payments service directly (CORS is enabled on payments service)
// This avoids nginx proxy redirect issues
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'https://payments.local'

const adminApi = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Store Auth0 client reference for token retrieval
let auth0ClientRef = null

/**
 * Initialize the adminApi with Auth0 client for token management
 * @param {Object} auth0Client - Auth0 client instance
 */
export const setupAdminApi = (auth0Client) => {
  auth0ClientRef = auth0Client
}

// Add request interceptor to include bearer token
// This interceptor is async to support token refresh
adminApi.interceptors.request.use(
  async (config) => {
    let token = localStorage.getItem('bearerToken')
    
    // If no token in localStorage and we have an Auth0 client, try to get a fresh token
    if (!token && auth0ClientRef) {
      try {
        const audience = import.meta.env.VITE_AUTH0_AUDIENCE || undefined
        const tokenResponse = await auth0ClientRef.getTokenSilently({ 
          detailedResponse: true,
          authorizationParams: {
            audience: audience
          }
        })
        // Always use access_token for API calls - ID tokens don't have the audience claim
        token = tokenResponse.access_token
        if (!token) {
          throw new Error('No access token received from Auth0')
        }
        localStorage.setItem('bearerToken', token)
      } catch (error) {
        console.error('Error getting access token from Auth0:', error)
      }
    }
    
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// Add response interceptor to handle 401 errors and refresh token
adminApi.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config
    
    // If we get a 401 and haven't retried yet, try to refresh the token
    if (error.response?.status === 401 && !originalRequest._retry && auth0ClientRef) {
      originalRequest._retry = true
      
      try {
        const audience = import.meta.env.VITE_AUTH0_AUDIENCE || undefined
        const tokenResponse = await auth0ClientRef.getTokenSilently({ 
          detailedResponse: true,
          authorizationParams: {
            audience: audience
          }
        })
        // Always use access_token for API calls - ID tokens don't have the audience claim
        const token = tokenResponse.access_token
        if (!token) {
          throw new Error('No access token received from Auth0')
        }
        localStorage.setItem('bearerToken', token)
        originalRequest.headers.Authorization = `Bearer ${token}`
        return adminApi(originalRequest)
      } catch (refreshError) {
        console.error('Error refreshing access token:', refreshError)
        // If refresh fails, clear the token and let the error propagate
        localStorage.removeItem('bearerToken')
        return Promise.reject(refreshError)
      }
    }
    
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
export const createPayoutForSeller = async (sellerId, currency = 'CAD') => {
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

// Catalog service endpoints (for inventory sync)
const CATALOG_API_BASE_URL = import.meta.env.VITE_CATALOG_API_BASE_URL || 'https://catalog.local'

// Create a separate axios instance for catalog service with token handling
const catalogApi = axios.create({
  baseURL: CATALOG_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor to include bearer token for catalog API
catalogApi.interceptors.request.use(
  async (config) => {
    let token = localStorage.getItem('bearerToken')
    
    // If no token in localStorage and we have an Auth0 client, try to get a fresh token
    if (!token && auth0ClientRef) {
      try {
        const audience = import.meta.env.VITE_AUTH0_AUDIENCE || undefined
        const tokenResponse = await auth0ClientRef.getTokenSilently({ 
          detailedResponse: true,
          authorizationParams: {
            audience: audience
          }
        })
        token = tokenResponse.access_token
        if (!token) {
          throw new Error('No access token received from Auth0')
        }
        localStorage.setItem('bearerToken', token)
      } catch (error) {
        console.error('Error getting access token from Auth0:', error)
      }
    }
    
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// Sync inventory endpoint (ADMIN only)
export const syncInventory = async () => {
  const response = await catalogApi.post('/api/catalog/products/sync-inventory')
  return response.data
}

export default adminApi

