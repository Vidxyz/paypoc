import axios from 'axios'

// Fulfillment service API base URL
const FULFILLMENT_API_BASE_URL = import.meta.env.VITE_FULFILLMENT_API_BASE_URL || 'https://fulfillment.local'

const fulfillmentApi = axios.create({
  baseURL: FULFILLMENT_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add response interceptor for error handling and token expiration detection
fulfillmentApi.interceptors.response.use(
  (response) => response,
  (error) => {
    // Handle 401 Unauthorized - token expired or missing
    if (error.response?.status === 401) {
      console.warn('Received 401 Unauthorized - forcing re-login')
      // Dispatch event to force logout and show login modal
      window.dispatchEvent(new CustomEvent('auth:force-login'))
      error.message = 'Your session has expired. Please log in again.'
    } else if (error.response?.data?.detail) {
      error.message = error.response.data.detail
    } else if (error.response?.data?.error) {
      error.message = error.response.data.error
    } else if (error.response?.data?.message) {
      error.message = error.response.data.message
    }
    return Promise.reject(error)
  }
)

/**
 * Get fulfillment API client with authentication
 * @param {Function} getAccessToken - Function to get access token from Auth0
 * @returns {Object} Fulfillment API client
 */
export const createFulfillmentApiClient = (getAccessToken) => {
  // Add request interceptor to include bearer token
  const requestInterceptor = fulfillmentApi.interceptors.request.use(
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
    (error) => {
      return Promise.reject(error)
    }
  )

  return {
    /**
     * Get list of shipments for the authenticated seller
     * @param {Object} options - Query options (limit)
     * @returns {Promise<Array>} Array of ShipmentResponse objects
     */
    getShipments: async (options = {}) => {
      const { limit = 50 } = options
      const params = new URLSearchParams()
      if (limit) {
        params.append('limit', limit.toString())
      }

      const response = await fulfillmentApi.get(`/api/seller/shipments?${params.toString()}`)
      return response.data
    },

    /**
     * Get shipment by ID
     * @param {string} shipmentId - Shipment UUID
     * @returns {Promise<Object>} ShipmentResponse
     */
    getShipment: async (shipmentId) => {
      const response = await fulfillmentApi.get(`/api/seller/shipments/${shipmentId}`)
      return response.data
    },

    /**
     * Update shipment status
     * @param {string} shipmentId - Shipment UUID
     * @param {string} status - New status (PENDING, PROCESSING, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, RETURNED)
     * @returns {Promise<Object>} ShipmentResponse
     */
    updateShipmentStatus: async (shipmentId, status) => {
      const response = await fulfillmentApi.put(
        `/api/seller/shipments/${shipmentId}/status`,
        { status },
      )
      return response.data
    },

    /**
     * Add or update tracking information
     * @param {string} shipmentId - Shipment UUID
     * @param {string} trackingNumber - Tracking number
     * @param {string} carrier - Carrier name (UPS, FEDEX, DHL, etc.)
     * @returns {Promise<Object>} ShipmentResponse
     */
    addTracking: async (shipmentId, trackingNumber, carrier) => {
      const response = await fulfillmentApi.put(
        `/api/seller/shipments/${shipmentId}/tracking`,
        { trackingNumber, carrier },
      )
      return response.data
    },

    // Cleanup function to remove interceptor
    cleanup: () => {
      fulfillmentApi.interceptors.request.eject(requestInterceptor)
    },
  }
}

export default fulfillmentApi
