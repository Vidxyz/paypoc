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

// Add request interceptor for error handling
paymentsApi.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.data?.error) {
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
    
    // Cleanup function to remove interceptor if needed
    cleanup: () => {
      paymentsApi.interceptors.request.eject(requestInterceptor)
    },
  }
}

export default paymentsApi

