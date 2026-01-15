import { createContext, useContext, useState, useEffect } from 'react'

const CartContext = createContext(null)

/**
 * Cart Context Provider
 * Manages cart state in frontend only (no backend integration yet)
 */
export function CartProvider({ children }) {
  const [cartItems, setCartItems] = useState([])

  // Load cart from localStorage on mount
  useEffect(() => {
    const savedCart = localStorage.getItem('cart')
    if (savedCart) {
      try {
        setCartItems(JSON.parse(savedCart))
      } catch (error) {
        console.error('Failed to load cart from localStorage:', error)
        localStorage.removeItem('cart')
      }
    }
  }, [])

  // Save cart to localStorage whenever it changes
  useEffect(() => {
    if (cartItems.length > 0) {
      localStorage.setItem('cart', JSON.stringify(cartItems))
    } else {
      localStorage.removeItem('cart')
    }
  }, [cartItems])

  /**
   * Add item to cart or update quantity if already exists
   * @param {Object} product - Product object
   * @param {number} quantity - Quantity to add
   */
  const addToCart = (product, quantity = 1) => {
    setCartItems((prevItems) => {
      const existingItem = prevItems.find((item) => item.productId === product.id)

      if (existingItem) {
        // Update quantity
        return prevItems.map((item) =>
          item.productId === product.id
            ? { ...item, quantity: item.quantity + quantity }
            : item
        )
      } else {
        // Add new item
        return [
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
    })
  }

  /**
   * Update item quantity in cart
   * @param {string} productId - Product UUID
   * @param {number} quantity - New quantity (must be > 0)
   */
  const updateQuantity = (productId, quantity) => {
    if (quantity <= 0) {
      removeFromCart(productId)
      return
    }

    setCartItems((prevItems) =>
      prevItems.map((item) =>
        item.productId === productId ? { ...item, quantity } : item
      )
    )
  }

  /**
   * Remove item from cart
   * @param {string} productId - Product UUID
   */
  const removeFromCart = (productId) => {
    setCartItems((prevItems) => prevItems.filter((item) => item.productId !== productId))
  }

  /**
   * Clear entire cart
   */
  const clearCart = () => {
    setCartItems([])
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

