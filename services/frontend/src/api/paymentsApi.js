import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://payments-service.payments-platform.svc.cluster.local:8080'

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

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
   * Get multiple payments by IDs
   * @param {string[]} paymentIds - Array of payment UUIDs
   * @returns {Promise<Array>} Array of payment data
   */
  getPayments: async (paymentIds) => {
    const promises = paymentIds.map(id => 
      paymentsApi.getPayment(id).catch(err => {
        console.error(`Failed to fetch payment ${id}:`, err)
        return null
      })
    )
    const results = await Promise.all(promises)
    return results.filter(payment => payment !== null && !payment.error)
  },
}

export default paymentsApi

