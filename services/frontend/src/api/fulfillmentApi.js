import axios from 'axios'

// Fulfillment service API base URL
const FULFILLMENT_API_BASE_URL = import.meta.env.VITE_FULFILLMENT_API_BASE_URL || 'https://fulfillment.local'

const fulfillmentApi = axios.create({
  baseURL: FULFILLMENT_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor for error handling
fulfillmentApi.interceptors.response.use(
  (response) => response,
  (error) => {
    // Extract error message from response
    if (error.response?.data?.error) {
      error.message = error.response.data.error
    } else if (error.response?.data?.detail) {
      error.message = error.response.data.detail
    }
    return Promise.reject(error)
  }
)

export const fulfillmentApiClient = {
  /**
   * Get tracking information for an order
   * @param {string} orderId - Order UUID
   * @param {string} token - JWT access token
   * @returns {Promise<Array>} Array of ShipmentResponse objects
   */
  getOrderTracking: async (orderId, token) => {
    const response = await fulfillmentApi.get(`/api/tracking/orders/${orderId}`, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    return response.data
  },

  /**
   * Get tracking information by tracking number
   * @param {string} trackingNumber - Tracking number
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} ShipmentResponse
   */
  getTrackingByNumber: async (trackingNumber, token) => {
    const response = await fulfillmentApi.get(`/api/tracking/${trackingNumber}`, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    return response.data
  },
}

export default fulfillmentApiClient
