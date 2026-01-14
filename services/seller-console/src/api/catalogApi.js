import axios from 'axios'

// Catalog service API base URL
const CATALOG_API_BASE_URL = import.meta.env.VITE_CATALOG_API_BASE_URL || 'https://catalog.local'

// Create axios instance
const catalogApi = axios.create({
  baseURL: CATALOG_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor for error handling
catalogApi.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.data?.detail) {
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
 * Get catalog API client with authentication
 * @param {Function} getAccessToken - Function to get access token from Auth0
 * @returns {Object} Catalog API client
 */
export const createCatalogApiClient = (getAccessToken) => {
  // Add request interceptor to include bearer token
  const requestInterceptor = catalogApi.interceptors.request.use(
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
     * Get seller's products
     * @param {Object} options - Query options
     * @param {string} options.status - Filter by status (DRAFT, ACTIVE, INACTIVE)
     * @returns {Promise<Array>} Array of products
     */
    getProducts: async (options = {}) => {
      const { status } = options
      const params = {}
      if (status) {
        params.status = status
      }
      const response = await catalogApi.get('/api/catalog/products', { params })
      return response.data
    },

    /**
     * Get product by ID
     * @param {string} productId - Product UUID
     * @returns {Promise<Object>} Product details
     */
    getProduct: async (productId) => {
      const response = await catalogApi.get(`/api/catalog/products/${productId}`)
      return response.data
    },

    /**
     * Create a new product
     * @param {Object} productData - Product data
     * @returns {Promise<Object>} Created product
     */
    createProduct: async (productData) => {
      const response = await catalogApi.post('/api/catalog/products', productData)
      return response.data
    },

    /**
     * Update a product
     * @param {string} productId - Product UUID
     * @param {Object} productData - Updated product data
     * @returns {Promise<Object>} Updated product
     */
    updateProduct: async (productId, productData) => {
      const response = await catalogApi.put(`/api/catalog/products/${productId}`, productData)
      return response.data
    },

    /**
     * Delete a product (soft delete)
     * @param {string} productId - Product UUID
     * @returns {Promise<void>}
     */
    deleteProduct: async (productId) => {
      await catalogApi.delete(`/api/catalog/products/${productId}`)
    },

    /**
     * Upload image file to Cloudinary
     * @param {File} file - Image file
     * @returns {Promise<string>} Cloudinary public_id
     */
    uploadImageFile: async (file) => {
      const formData = new FormData()
      formData.append('file', file)
      
      const token = await getAccessToken()
      const response = await catalogApi.post('/api/catalog/images/upload', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
          Authorization: token ? `Bearer ${token}` : undefined,
        },
      })
      return response.data.public_id
    },

    /**
     * Upload image from URL to Cloudinary
     * @param {string} imageUrl - Image URL
     * @returns {Promise<string>} Cloudinary public_id
     */
    uploadImageUrl: async (imageUrl) => {
      const token = await getAccessToken()
      const response = await catalogApi.post(
        '/api/catalog/images/upload-url',
        { url: imageUrl },
        {
          headers: {
            Authorization: token ? `Bearer ${token}` : undefined,
          },
        }
      )
      return response.data.public_id
    },

    /**
     * Get image URL from Cloudinary public_id
     * @param {string} publicId - Cloudinary public_id
     * @param {string} variant - Optional transformation variant
     * @returns {Promise<string>} Image URL
     */
    getImageUrl: async (publicId, variant = null) => {
      const params = variant ? { variant } : {}
      const response = await catalogApi.get(`/api/catalog/images/${publicId}/url`, { params })
      return response.data.url
    },

    /**
     * Get all categories
     * @returns {Promise<Array>} Array of categories
     */
    getCategories: async () => {
      const response = await catalogApi.get('/api/catalog/categories')
      return response.data
    },

    // Cleanup function to remove interceptor if needed
    cleanup: () => {
      catalogApi.interceptors.request.eject(requestInterceptor)
    },
  }
}

export default catalogApi

