import axios from 'axios'
import { useAuth0 } from '../auth/Auth0Provider'

// Payments service API base URL
const PAYMENTS_API_BASE_URL = import.meta.env.VITE_PAYMENTS_API_BASE_URL || 'https://payments.local'

// Create axios instance
const paymentsApi = axios.create({
  baseURL: PAYMENTS_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add response interceptor for error handling and token expiration detection
paymentsApi.interceptors.response.use(
  (response) => response,
  (error) => {
    // Handle 401 Unauthorized - token expired or missing
    if (error.response?.status === 401) {
      console.warn('Received 401 Unauthorized - forcing re-login')
      // Dispatch event to force logout and show login modal
      window.dispatchEvent(new CustomEvent('auth:force-login'))
      error.message = 'Your session has expired. Please log in again.'
    } else if (error.response?.data?.error) {
      error.message = error.response.data.error
    } else if (error.response?.data?.details) {
      error.message = JSON.stringify(error.response.data.details)
    }
    return Promise.reject(error)
  }
)

/**
 * Get payments API client with authentication
 * @param {Function} getAccessToken - Function to get access token from Auth0
 * @returns {Object} Payments API client
 */
export const createPaymentsApiClient = (getAccessToken) => {
  // Add request interceptor to include bearer token
  const requestInterceptor = paymentsApi.interceptors.request.use(
    async (config) => {
      try {
        const token = await getAccessToken()
        if (token) {
          config.headers.Authorization = `Bearer ${token}`
        }
      } catch (error) {
        console.error('Failed to get access token:', error)
      }
      return config
    },
    (error) => Promise.reject(error)
  )

  return {
    /**
     * Get seller's Stripe account information
     * @returns {Promise<Array>} Array of seller Stripe accounts (one per currency)
     */
    getSellerStripeAccounts: async () => {
      const response = await paymentsApi.get('/seller/profile/stripe-accounts')
      return response.data
    },
    
    /**
     * Update seller's Stripe account ID for a specific currency
     * @param {string} currency - Currency code (e.g., 'USD')
     * @param {string} stripeAccountId - Stripe account ID (e.g., 'acct_1234567890')
     * @returns {Promise<Object>} Updated Stripe account information
     */
    updateStripeAccount: async (currency, stripeAccountId) => {
      const response = await paymentsApi.put(`/seller/profile/stripe-accounts/${currency}`, {
        stripeAccountId,
      })
      return response.data
    },
    
    /**
     * Get seller's account balance
     * @returns {Promise<Object>} Balance information with accountId, currency, balanceCents
     */
    getSellerBalance: async () => {
      const response = await paymentsApi.get('/payments/balance')
      return response.data
    },
    
    /**
     * Get seller's payments
     * @param {Object} options - Query options
     * @param {number} options.page - Page number (0-indexed, default: 0)
     * @param {number} options.size - Page size (default: 50)
     * @param {string} options.sortBy - Field to sort by (default: "createdAt")
     * @param {string} options.sortDirection - Sort direction: "ASC" or "DESC" (default: "DESC")
     * @returns {Promise<Object>} Response with payments array, page, size, and total
     */
    getSellerPayments: async (options = {}) => {
      const { page = 0, size = 50, sortBy = 'createdAt', sortDirection = 'DESC' } = options
      const response = await paymentsApi.get('/payments', {
        params: {
          page,
          size,
          sortBy,
          sortDirection,
        },
      })
      return response.data
    },
    
    /**
     * Get seller's payouts with pagination
     * @param {Object} options - Query options
     * @param {number} options.page - Page number (0-indexed, default: 0)
     * @param {number} options.size - Page size (default: 20)
     * @returns {Promise<Object>} Response with payouts array, page, size, total, totalPages
     */
    getSellerPayouts: async (options = {}) => {
      const { page = 0, size = 20 } = options
      const response = await paymentsApi.get('/seller/profile/payouts', {
        params: {
          page,
          size,
        },
      })
      return response.data
    },
    
    /**
     * Get refunds for a payment
     * @param {string} paymentId - Payment ID
     * @returns {Promise<Object>} Response with refunds array
     */
    getRefundsForPayment: async (paymentId) => {
      const response = await paymentsApi.get(`/admin/payments/${paymentId}/refunds`)
      return response.data
    },
    
    /**
     * Get refunds for multiple payments in a single batch call (seller-specific)
     * @param {Array<string>} paymentIds - Array of payment IDs
     * @returns {Promise<Object>} Response with refundsByPaymentId map (filtered for this seller)
     */
    getRefundsForPayments: async (paymentIds) => {
      if (!paymentIds || paymentIds.length === 0) {
        return {}
      }
      const response = await paymentsApi.post('/seller/profile/refunds/batch', {
        paymentIds: paymentIds,
      })
      return response.data.refundsByPaymentId || {}
    },
    
    // Cleanup function to remove interceptor if needed
    cleanup: () => {
      paymentsApi.interceptors.request.eject(requestInterceptor)
    },
  }
}

export default paymentsApi

