import axios from 'axios'

// Order service API base URL
const ORDER_API_BASE_URL = import.meta.env.VITE_ORDER_API_BASE_URL || 'https://order.local'

// Create axios instance
const orderApi = axios.create({
  baseURL: ORDER_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add response interceptor for error handling and token expiration detection
orderApi.interceptors.response.use(
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
 * Get order API client with authentication
 * @param {Function} getAccessToken - Function to get access token from Auth0
 * @returns {Object} Order API client
 */
export const createOrderApiClient = (getAccessToken) => {
  // Add request interceptor to include bearer token
  const requestInterceptor = orderApi.interceptors.request.use(
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
     * Get list of orders for the authenticated seller
     * @param {Object} options - Query options (page, pageSize)
     * @returns {Promise<Object>} ListOrdersResponse with orders array, total, page, pageSize, totalPages
     */
    getOrders: async (options = {}) => {
      const { page = 0, pageSize = 20 } = options
      const params = new URLSearchParams()
      params.append('page', page.toString())
      params.append('pageSize', pageSize.toString())

      const response = await orderApi.get(`/api/orders?${params.toString()}`)
      return response.data
    },

    /**
     * Get order details by ID
     * @param {string} orderId - Order UUID
     * @returns {Promise<Object>} OrderResponse
     */
    getOrder: async (orderId) => {
      const response = await orderApi.get(`/api/orders/${orderId}`)
      return response.data
    },

    // Cleanup function to remove interceptor
    cleanup: () => {
      orderApi.interceptors.request.eject(requestInterceptor)
    },
  }
}

export default orderApi
