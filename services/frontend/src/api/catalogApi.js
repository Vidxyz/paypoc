import axios from 'axios'

// Catalog service API base URL
const CATALOG_API_BASE_URL = import.meta.env.VITE_CATALOG_API_BASE_URL || 'https://catalog.local'

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
    // Extract error message from response
    if (error.response?.data?.detail) {
      error.message = error.response.data.detail
    } else if (error.response?.data?.error) {
      error.message = error.response.data.error
    }
    return Promise.reject(error)
  }
)

export const catalogApiClient = {
  /**
   * Browse products (public endpoint, no auth required)
   * @param {Object} params - Query parameters
   * @param {string[]} params.category_ids - Optional array of category UUIDs to filter by (OR logic)
   * @param {number} params.page - Page number (1-indexed, default: 1)
   * @param {number} params.page_size - Items per page (default: 20, max: 100)
   * @returns {Promise<Object>} ProductListResponse with products, total, page, page_size, has_next
   */
  browseProducts: async (params = {}) => {
    const { category_ids, page = 1, page_size = 20, sort_by } = params
    const queryParams = new URLSearchParams()
    if (category_ids && category_ids.length > 0) {
      category_ids.forEach(id => queryParams.append('category_ids', id))
    }
    queryParams.append('page', page.toString())
    queryParams.append('page_size', page_size.toString())
    if (sort_by) {
      queryParams.append('sort_by', sort_by)
    }
    
    const response = await catalogApi.get(`/api/catalog/products/browse?${queryParams.toString()}`)
    return response.data
  },

  /**
   * Get product by ID (public endpoint, no auth required)
   * @param {string} productId - Product UUID
   * @returns {Promise<Object>} ProductResponse
   */
  getProduct: async (productId) => {
    const response = await catalogApi.get(`/api/catalog/products/${productId}`)
    return response.data
  },

  /**
   * Get all categories (requires authentication)
   * @param {string} token - JWT access token
   * @returns {Promise<Array>} List of categories
   */
  getCategories: async (token) => {
    const response = await catalogApi.get('/api/catalog/categories', {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    return response.data
  },

  /**
   * Get category by ID (requires authentication)
   * @param {string} categoryId - Category UUID
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} CategoryResponse
   */
  getCategory: async (categoryId, token) => {
    const response = await catalogApi.get(`/api/catalog/categories/${categoryId}`, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    return response.data
  },
}

export default catalogApiClient

