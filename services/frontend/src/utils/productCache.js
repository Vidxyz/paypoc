/**
 * Utility functions for caching product information in localStorage
 */

const PRODUCT_CACHE_KEY = 'productCache'
const CACHE_EXPIRY_MS = 60 * 60 * 1000 // 1 hour

/**
 * Store product information in localStorage cache
 * @param {Object} product - Product object with id, name, images, price_cents, currency, etc.
 */
export function cacheProduct(product) {
  if (!product || !product.id) return

  try {
    const cache = getProductCache()
    cache[product.id] = {
      ...product,
      cachedAt: Date.now(),
    }
    localStorage.setItem(PRODUCT_CACHE_KEY, JSON.stringify(cache))
  } catch (error) {
    console.warn('Failed to cache product:', error)
    // localStorage might be full or unavailable - continue without caching
  }
}

/**
 * Cache multiple products at once
 * @param {Array} products - Array of product objects
 */
export function cacheProducts(products) {
  if (!Array.isArray(products)) return
  products.forEach(product => cacheProduct(product))
}

/**
 * Get product from cache
 * @param {string} productId - Product UUID
 * @returns {Object|null} Cached product or null if not found or expired
 */
export function getCachedProduct(productId) {
  if (!productId) return null

  try {
    const cache = getProductCache()
    const cached = cache[productId]

    if (!cached) return null

    // Check if cache is expired
    const age = Date.now() - (cached.cachedAt || 0)
    if (age > CACHE_EXPIRY_MS) {
      // Remove expired entry
      delete cache[productId]
      localStorage.setItem(PRODUCT_CACHE_KEY, JSON.stringify(cache))
      return null
    }

    // Return product without cache metadata
    const { cachedAt, ...product } = cached
    return product
  } catch (error) {
    console.warn('Failed to get cached product:', error)
    return null
  }
}

/**
 * Get all cached products
 * @returns {Object} Map of productId -> product
 */
function getProductCache() {
  try {
    const cached = localStorage.getItem(PRODUCT_CACHE_KEY)
    return cached ? JSON.parse(cached) : {}
  } catch (error) {
    console.warn('Failed to read product cache:', error)
    return {}
  }
}

/**
 * Clear expired products from cache
 */
export function clearExpiredProducts() {
  try {
    const cache = getProductCache()
    const now = Date.now()
    let hasChanges = false

    Object.keys(cache).forEach(productId => {
      const cached = cache[productId]
      const age = now - (cached.cachedAt || 0)
      if (age > CACHE_EXPIRY_MS) {
        delete cache[productId]
        hasChanges = true
      }
    })

    if (hasChanges) {
      localStorage.setItem(PRODUCT_CACHE_KEY, JSON.stringify(cache))
    }
  } catch (error) {
    console.warn('Failed to clear expired products:', error)
  }
}

/**
 * Clear all cached products
 */
export function clearProductCache() {
  try {
    localStorage.removeItem(PRODUCT_CACHE_KEY)
  } catch (error) {
    console.warn('Failed to clear product cache:', error)
  }
}
