import axios from 'axios'

// Inventory service API base URL
const INVENTORY_API_BASE_URL = import.meta.env.VITE_INVENTORY_API_BASE_URL || 'https://inventory.local'

// Create axios instance
const inventoryApi = axios.create({
  baseURL: INVENTORY_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor for error handling
inventoryApi.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.data?.error) {
      error.message = error.response.data.error
    } else if (error.response?.data?.message) {
      error.message = error.response.data.message
    }
    return Promise.reject(error)
  }
)

/**
 * Get inventory API client with authentication
 * @param {Function} getAccessToken - Function to get access token from Auth0
 * @returns {Object} Inventory API client
 */
export const createInventoryApiClient = (getAccessToken) => {
  // Add request interceptor to include bearer token
  const requestInterceptor = inventoryApi.interceptors.request.use(
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
     * Get stock by product ID
     * @param {string} productId - Product UUID
     * @returns {Promise<Object>} Stock information
     */
    getStockByProductId: async (productId) => {
      const response = await inventoryApi.get(`/api/inventory/stock/${productId}`)
      return response.data
    },

    /**
     * Get stock by seller ID and SKU
     * @param {string} sellerId - Seller ID (email)
     * @param {string} sku - Product SKU
     * @returns {Promise<Object>} Stock information
     */
    getStockBySellerAndSku: async (sellerId, sku) => {
      const response = await inventoryApi.get(`/api/inventory/stock/seller/${sellerId}/sku/${sku}`)
      return response.data
    },

    /**
     * Create or update stock for a product
     * @param {string} productId - Product UUID
     * @param {Object} stockData - Stock data
     * @param {string} stockData.sku - Product SKU
     * @param {number} stockData.quantity - Stock quantity
     * @returns {Promise<Object>} Updated stock information
     */
    createOrUpdateStock: async (productId, stockData) => {
      const response = await inventoryApi.put(`/api/inventory/stock/${productId}`, stockData)
      return response.data
    },

    /**
     * Adjust stock quantity
     * @param {string} inventoryId - Inventory UUID
     * @param {number} delta - Quantity delta (positive to add, negative to remove)
     * @returns {Promise<Object>} Updated stock information
     */
    adjustStock: async (inventoryId, delta) => {
      const response = await inventoryApi.post(`/api/inventory/stock/${inventoryId}/adjust`, { delta })
      return response.data
    },

    /**
     * Get low stock items
     * @returns {Promise<Array>} Array of low stock items
     */
    getLowStockItems: async () => {
      const response = await inventoryApi.get('/api/inventory/low-stock')
      return response.data
    },

    // Cleanup function to remove interceptor if needed
    cleanup: () => {
      inventoryApi.interceptors.request.eject(requestInterceptor)
    },
  }
}

export default inventoryApi

