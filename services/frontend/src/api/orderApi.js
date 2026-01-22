import axios from 'axios'

// Order service API base URL
const ORDER_API_BASE_URL = import.meta.env.VITE_ORDER_API_BASE_URL || 'https://order.local'

const orderApi = axios.create({
  baseURL: ORDER_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor for error handling
orderApi.interceptors.response.use(
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

export const orderApiClient = {
  /**
   * Get list of orders for the authenticated user
   * @param {string} token - JWT access token
   * @param {Object} options - Query options (page, pageSize, buyerId filter)
   * @returns {Promise<Object>} ListOrdersResponse with orders array, total, page, pageSize, totalPages
   */
  getOrders: async (token, options = {}) => {
    const { page = 0, pageSize = 20, buyerId } = options
    const params = new URLSearchParams()
    params.append('page', page.toString())
    params.append('page_size', pageSize.toString()) // API expects snake_case
    if (buyerId) {
      params.append('buyer_id', buyerId) // API expects snake_case
    }

    const response = await orderApi.get(`/api/orders?${params.toString()}`, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    // Normalize response keys to camelCase for frontend consistency
    const data = response.data
    if (data.total_pages !== undefined) {
      data.totalPages = data.total_pages
    }
    if (data.page_size !== undefined) {
      data.pageSize = data.page_size
    }
    return data
  },

  /**
   * Get order details by ID
   * @param {string} orderId - Order UUID
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} OrderResponse
   */
  getOrder: async (orderId, token) => {
    const response = await orderApi.get(`/api/orders/${orderId}`, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    return response.data
  },

  /**
   * Get invoice PDF for an order
   * @param {string} orderId - Order UUID
   * @param {string} token - JWT access token
   * @returns {Promise<Blob>} PDF blob
   */
  getInvoice: async (orderId, token) => {
    const response = await orderApi.get(`/api/orders/${orderId}/invoice`, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
      responseType: 'blob',
    })
    return response.data
  },
}

export default orderApiClient
