import axios from 'axios'

// Use /api which nginx will proxy to the payments service
// For browser access, this will work whether frontend is at buyit.local or payments.local
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor to include bearer token
api.interceptors.request.use(
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

export const paymentsApi = {
  /**
   * Create a new payment
   * @param {Object} paymentData - Payment creation data
   * @returns {Promise} Payment response with client_secret
   */
  createPayment: async (paymentData) => {
    const response = await api.post('/payments', paymentData)
    return response.data
  },

  /**
   * Get a payment by ID
   * @param {string} paymentId - Payment UUID
   * @returns {Promise} Payment data
   */
  getPayment: async (paymentId) => {
    const response = await api.get(`/payments/${paymentId}`)
    return response.data
  },

  /**
   * Get payments for the authenticated user
   * @param {Object} options - Query options
   * @param {number} options.page - Page number (0-indexed, default: 0)
   * @param {number} options.size - Page size (default: 50)
   * @param {string} options.sortBy - Field to sort by (default: "createdAt")
   * @param {string} options.sortDirection - Sort direction: "ASC" or "DESC" (default: "DESC")
   * @returns {Promise<Object>} Response with payments array, page, size, and total
   */
  getPayments: async (options = {}) => {
    const { page = 0, size = 50, sortBy = 'createdAt', sortDirection = 'DESC' } = options
    const response = await api.get('/payments', {
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
   * Get chargebacks for a payment
   * @param {string} paymentId - Payment UUID
   * @returns {Promise} List of chargebacks
   */
  getChargebacksForPayment: async (paymentId) => {
    const response = await api.get(`/chargebacks/payments/${paymentId}`)
    return response.data
  },

  /**
   * Get a chargeback by ID
   * @param {string} chargebackId - Chargeback UUID
   * @returns {Promise} Chargeback data
   */
  getChargeback: async (chargebackId) => {
    const response = await api.get(`/chargebacks/${chargebackId}`)
    return response.data
  },
}

export default paymentsApi

