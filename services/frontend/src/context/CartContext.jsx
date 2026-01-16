import { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { useAuth0 } from '../auth/Auth0Provider'
import { cartApiClient } from '../api/cartApi'
import { catalogApiClient } from '../api/catalogApi'
import { getCachedProduct, cacheProduct } from '../utils/productCache'

const CartContext = createContext(null)

/**
 * Cart Context Provider
 * Manages cart state using the cart service backend
 */
export function CartProvider({ children }) {
  const { isAuthenticated, getAccessToken } = useAuth0()
  const [cartItems, setCartItems] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  /**
   * Load cart from localStorage (fallback)
   */
  const loadCartFromLocalStorage = useCallback(() => {
    try {
      const savedCart = localStorage.getItem('cart')
      if (savedCart) {
        const cartItems = JSON.parse(savedCart)
        setCartItems(cartItems)
        return true
      }
    } catch (error) {
      console.error('Failed to load cart from localStorage:', error)
    }
    return false
  }, [])

  /**
   * Save cart to localStorage (fallback)
   */
  const saveCartToLocalStorage = useCallback((items) => {
    try {
      if (items && items.length > 0) {
        localStorage.setItem('cart', JSON.stringify(items))
      } else {
        localStorage.removeItem('cart')
      }
    } catch (error) {
      console.warn('Failed to save cart to localStorage:', error)
    }
  }, [])

  /**
   * Get product details, checking cache first, then fetching if needed
   */
  const getProductDetails = useCallback(async (productId) => {
    // Check cache first
    const cached = getCachedProduct(productId)
    if (cached) {
      return cached
    }

    // Fetch from catalog service
    try {
      const product = await catalogApiClient.getProduct(productId)
      // Cache it for future use
      cacheProduct(product)
      return product
    } catch (err) {
      console.error(`Failed to fetch product details for ${productId}:`, err)
      return null
    }
  }, [])

  /**
   * Load cart from cart service (with localStorage fallback)
   */
  const loadCart = useCallback(async () => {
    if (!isAuthenticated) {
      // Try localStorage fallback when not authenticated
      loadCartFromLocalStorage()
      return
    }

    setLoading(true)
    setError(null)
    
    try {
      const token = await getAccessToken()
      if (!token) {
        console.warn('No access token available for cart operations, trying localStorage')
        if (!loadCartFromLocalStorage()) {
          setError('Please log in to access your cart')
        }
        return
      }

      const cartResponse = await cartApiClient.getCart(token)
      
      // Fetch product details for each item, using cache first
      const itemsWithDetails = await Promise.all(
        cartResponse.items.map(async (item) => {
          const product = await getProductDetails(item.productId)
          
          return {
            itemId: item.itemId,
            productId: item.productId,
            productName: product?.name || `Product ${item.sku}`,
            productImage: product?.images?.[0] || null,
            priceCents: item.priceCents,
            currency: item.currency,
            quantity: item.quantity,
            sku: item.sku,
            sellerId: item.sellerId,
          }
        })
      )

      setCartItems(itemsWithDetails)
      // Also save to localStorage as backup
      saveCartToLocalStorage(itemsWithDetails)
    } catch (err) {
      console.error('Failed to load cart from service:', err)
      // Fallback to localStorage
      if (loadCartFromLocalStorage()) {
        setError('Cart service unavailable. Showing cached cart. Changes may not be saved.')
      } else {
        setError(err.message || 'Failed to load cart')
      }
    } finally {
      setLoading(false)
    }
  }, [isAuthenticated, getAccessToken, loadCartFromLocalStorage, saveCartToLocalStorage, getProductDetails])

  // Load cart from service on mount and when authentication changes
  useEffect(() => {
    if (isAuthenticated) {
      loadCart()
    } else {
      // Clear cart when user logs out
      setCartItems([])
    }
  }, [isAuthenticated, loadCart])

  /**
   * Add item to cart or update quantity if already exists
   * @param {Object} product - Product object
   * @param {number} quantity - Quantity to add
   */
  const addToCart = async (product, quantity = 1) => {
    // Cache product info for future use
    cacheProduct(product)

    // If not authenticated, use localStorage only
    if (!isAuthenticated) {
      setCartItems((prevItems) => {
        const existingItem = prevItems.find((item) => item.productId === product.id)
        let newItems

        if (existingItem) {
          newItems = prevItems.map((item) =>
            item.productId === product.id
              ? { ...item, quantity: item.quantity + quantity }
              : item
          )
        } else {
          newItems = [
            ...prevItems,
            {
              productId: product.id,
              productName: product.name,
              productImage: product.images?.[0] || null,
              priceCents: product.price_cents,
              currency: product.currency,
              quantity: quantity,
              sku: product.sku,
              sellerId: product.seller_id,
            },
          ]
        }
        saveCartToLocalStorage(newItems)
        return newItems
      })
      return
    }

    setLoading(true)
    setError(null)
    try {
      const token = await getAccessToken()
      if (!token) {
        throw new Error('No access token available')
      }

      // Check if product already exists in cart
      const existingItem = cartItems.find((item) => item.productId === product.id)

      let cartResponse
      if (existingItem) {
        // Update quantity using itemId
        const newQuantity = existingItem.quantity + quantity
        cartResponse = await cartApiClient.updateItem(existingItem.itemId, newQuantity, token)
      } else {
        // Add new item
        cartResponse = await cartApiClient.addItem(product.id, quantity, token)
      }

      // Reload cart to get updated state from backend
      await loadCart()
    } catch (err) {
      console.error('Failed to add item to cart service:', err)
      // Fallback to localStorage
      setCartItems((prevItems) => {
        const existingItem = prevItems.find((item) => item.productId === product.id)
        let newItems

        if (existingItem) {
          newItems = prevItems.map((item) =>
            item.productId === product.id
              ? { ...item, quantity: item.quantity + quantity }
              : item
          )
        } else {
          newItems = [
            ...prevItems,
            {
              productId: product.id,
              productName: product.name,
              productImage: product.images?.[0] || null,
              priceCents: product.price_cents,
              currency: product.currency,
              quantity: quantity,
              sku: product.sku,
              sellerId: product.seller_id,
            },
          ]
        }
        saveCartToLocalStorage(newItems)
        return newItems
      })
      setError('Cart service unavailable. Item added to local cart. Please try again later.')
    } finally {
      setLoading(false)
    }
  }

  /**
   * Update item quantity in cart
   * @param {string} productId - Product UUID (for finding the item)
   * @param {number} quantity - New quantity (must be > 0)
   */
  const updateQuantity = async (productId, quantity) => {
    if (quantity <= 0) {
      removeFromCart(productId)
      return
    }

    // If not authenticated, use localStorage only
    if (!isAuthenticated) {
      setCartItems((prevItems) => {
        const newItems = prevItems.map((item) =>
          item.productId === productId ? { ...item, quantity } : item
        )
        saveCartToLocalStorage(newItems)
        return newItems
      })
      return
    }

    setLoading(true)
    setError(null)
    try {
      const token = await getAccessToken()
      if (!token) {
        throw new Error('No access token available')
      }

      const item = cartItems.find((item) => item.productId === productId)
      if (!item) {
        throw new Error('Item not found in cart')
      }

      await cartApiClient.updateItem(item.itemId, quantity, token)
      
      // Reload cart to get updated state from backend
      await loadCart()
    } catch (err) {
      console.error('Failed to update item quantity in service:', err)
      // Fallback to localStorage
      setCartItems((prevItems) => {
        const newItems = prevItems.map((item) =>
          item.productId === productId ? { ...item, quantity } : item
        )
        saveCartToLocalStorage(newItems)
        return newItems
      })
      setError('Cart service unavailable. Updated local cart. Please try again later.')
    } finally {
      setLoading(false)
    }
  }

  /**
   * Remove item from cart
   * @param {string} productId - Product UUID (for finding the item)
   */
  const removeFromCart = async (productId) => {
    // If not authenticated, use localStorage only
    if (!isAuthenticated) {
      setCartItems((prevItems) => {
        const newItems = prevItems.filter((item) => item.productId !== productId)
        saveCartToLocalStorage(newItems)
        return newItems
      })
      return
    }

    setLoading(true)
    setError(null)
    try {
      const token = await getAccessToken()
      if (!token) {
        throw new Error('No access token available')
      }

      const item = cartItems.find((item) => item.productId === productId)
      if (!item) {
        throw new Error('Item not found in cart')
      }

      await cartApiClient.removeItem(item.itemId, token)
      
      // Reload cart to get updated state from backend
      await loadCart()
    } catch (err) {
      console.error('Failed to remove item from cart service:', err)
      // Fallback to localStorage
      setCartItems((prevItems) => {
        const newItems = prevItems.filter((item) => item.productId !== productId)
        saveCartToLocalStorage(newItems)
        return newItems
      })
      setError('Cart service unavailable. Removed from local cart. Please try again later.')
    } finally {
      setLoading(false)
    }
  }

  /**
   * Clear entire cart
   */
  const clearCart = async () => {
    // Clear localStorage immediately
    setCartItems([])
    saveCartToLocalStorage([])

    if (!isAuthenticated) {
      return
    }

    setLoading(true)
    setError(null)
    try {
      const token = await getAccessToken()
      if (!token) {
        throw new Error('No access token available')
      }

      // Remove all items one by one (cart service doesn't have a clear all endpoint)
      await Promise.all(
        cartItems.map((item) => cartApiClient.removeItem(item.itemId, token))
      )
      
      // Reload cart (should be empty now)
      await loadCart()
    } catch (err) {
      console.error('Failed to clear cart in service:', err)
      setError('Cart service unavailable. Cleared local cart. Please try again later.')
    } finally {
      setLoading(false)
    }
  }

  /**
   * Get total number of items in cart
   */
  const getTotalItems = () => {
    return cartItems.reduce((total, item) => total + item.quantity, 0)
  }

  /**
   * Get total price in cents
   */
  const getTotalPrice = () => {
    return cartItems.reduce((total, item) => total + item.priceCents * item.quantity, 0)
  }

  /**
   * Check if product is in cart
   * @param {string} productId - Product UUID
   * @returns {boolean}
   */
  const isInCart = (productId) => {
    return cartItems.some((item) => item.productId === productId)
  }

  /**
   * Get quantity of a product in cart
   * @param {string} productId - Product UUID
   * @returns {number}
   */
  const getQuantity = (productId) => {
    const item = cartItems.find((item) => item.productId === productId)
    return item ? item.quantity : 0
  }

  const value = {
    cartItems,
    addToCart,
    updateQuantity,
    removeFromCart,
    clearCart,
    getTotalItems,
    getTotalPrice,
    isInCart,
    getQuantity,
    loading,
    error,
    refreshCart: loadCart, // Expose loadCart for manual refresh
  }

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}

/**
 * Hook to use cart context
 */
export function useCart() {
  const context = useContext(CartContext)
  if (!context) {
    throw new Error('useCart must be used within a CartProvider')
  }
  return context
}

