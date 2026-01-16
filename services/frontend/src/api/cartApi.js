import axios from 'axios'

// Cart service API base URL
const CART_API_BASE_URL = import.meta.env.VITE_CART_API_BASE_URL || 'https://cart.local'

const cartApi = axios.create({
  baseURL: CART_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor for error handling
cartApi.interceptors.response.use(
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

export const cartApiClient = {
  /**
   * Get current cart (requires authentication)
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} CartResponse
   */
  getCart: async (token) => {
    const response = await cartApi.get('/api/cart', {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    return response.data
  },

  /**
   * Add item to cart (requires authentication)
   * @param {string} productId - Product UUID
   * @param {number} quantity - Quantity to add
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} CartResponse
   */
  addItem: async (productId, quantity, token) => {
    const response = await cartApi.post(
      '/api/cart/items',
      {
        productId,
        quantity,
      },
      {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      }
    )
    return response.data
  },

  /**
   * Update item quantity in cart (requires authentication)
   * @param {string} itemId - Cart item UUID
   * @param {number} quantity - New quantity
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} CartResponse
   */
  updateItem: async (itemId, quantity, token) => {
    const response = await cartApi.put(
      `/api/cart/items/${itemId}`,
      {
        quantity,
      },
      {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      }
    )
    return response.data
  },

  /**
   * Remove item from cart (requires authentication)
   * @param {string} itemId - Cart item UUID
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} CartResponse
   */
  removeItem: async (itemId, token) => {
    const response = await cartApi.delete(`/api/cart/items/${itemId}`, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    return response.data
  },

  /**
   * Initiate checkout (requires authentication)
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} CheckoutResponse with orderId, paymentId, clientSecret, checkoutUrl
   */
  checkout: async (token) => {
    const response = await cartApi.post(
      '/api/cart/checkout',
      {},
      {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      }
    )
    return response.data
  },
}

export default cartApiClient
