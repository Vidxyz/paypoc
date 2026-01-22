import { useState, useEffect, useRef } from 'react'
import { loadStripe } from '@stripe/stripe-js'
import { Elements, CardElement, useStripe, useElements } from '@stripe/react-stripe-js'
import {
  Container,
  Card,
  CardContent,
  Typography,
  TextField,
  Button,
  Box,
  Alert,
  List,
  ListItem,
  ListItemText,
  ListItemSecondaryAction,
  IconButton,
  Divider,
  CircularProgress,
  Collapse,
  Grid,
} from '@mui/material'
import DeleteIcon from '@mui/icons-material/Delete'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import { useCart } from '../context/CartContext'
import { useAuth0 } from '../auth/Auth0Provider'
import { cartApiClient } from '../api/cartApi'
import { orderApiClient } from '../api/orderApi'
import { useNavigate } from 'react-router-dom'
import ProductModal from '../components/ProductModal'
import { catalogApiClient } from '../api/catalogApi'

const stripePromise = loadStripe(import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY || 'missing_stripe_publishable_key')

// Production mode flag - set to false for nonproduction (development/testing)
const IS_PRODUCTION = import.meta.env.VITE_IS_PRODUCTION === 'true' || false

function PaymentForm({ buyerId, clientSecret, orderId, paymentId, onSuccess }) {
  const stripe = useStripe()
  const elements = useElements()
  const { user } = useAuth0()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    if (!stripe || !elements) {
      setError('Stripe not loaded. Please wait...')
      setLoading(false)
      return
    }

    // Validate email - must be a valid email, not a UUID or other invalid value
    const userEmail = user?.email
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (!userEmail || !emailRegex.test(userEmail)) {
      console.error('Invalid email from Auth0 user:', userEmail, 'User object:', user)
      setError('Valid email address is required for payment. Please ensure you are logged in with a valid email.')
      setLoading(false)
      return
    }

    try {
      const cardElement = elements.getElement(CardElement)
      if (!cardElement) {
        throw new Error('Card element not found')
      }

      // Build minimal billing_details - only email is required
      const billingDetails = {
        email: userEmail,
      }
      
      // Optionally include name if available
      if (user?.name) {
        billingDetails.name = user.name
      }

      const { error: confirmError, paymentIntent } = await stripe.confirmCardPayment(
        clientSecret,
        {
          payment_method: {
            card: cardElement,
            billing_details: billingDetails,
          },
        }
      )

      if (confirmError) {
        setError(confirmError.message || 'Payment failed')
        setLoading(false)
        return
      }

      if (paymentIntent.status === 'succeeded' || paymentIntent.status === 'requires_capture') {
        onSuccess(paymentIntent, orderId, paymentId)
      } else {
        setError(`Payment status: ${paymentIntent.status}`)
      }
    } catch (err) {
      setError(err.message || 'An error occurred during payment')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="h6" gutterBottom sx={{ mb: 2 }}>
          Payment Details
        </Typography>

        {error && (
          <Alert severity="error" sx={{ mb: 3 }}>
            {error}
          </Alert>
        )}

        <Box sx={{ mb: 3 }}>
          <Typography variant="subtitle2" gutterBottom>
            Card Details
          </Typography>
          <Box
            sx={{
              p: 2,
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: 1,
              bgcolor: 'background.paper',
            }}
          >
            <CardElement
              options={{
                style: {
                  base: {
                    fontSize: '16px',
                    color: '#424770',
                    '::placeholder': {
                      color: '#aab7c4',
                    },
                  },
                  invalid: {
                    color: '#9e2146',
                  },
                },
              }}
            />
          </Box>
        </Box>

        <Button
          type="submit"
          fullWidth
          variant="contained"
          size="large"
          disabled={loading || !stripe || !elements}
          onClick={handleSubmit}
        >
          {loading ? 'Processing Payment...' : 'Complete Payment'}
        </Button>
      </CardContent>
    </Card>
  )
}

function CheckoutForm({ buyerId }) {
  const { cartItems, updateQuantity, removeFromCart, getTotalPrice, getTotalItems, loading: cartLoading, clearCart } = useCart()
  const { getAccessToken } = useAuth0()
  const navigate = useNavigate()
  const [showPayment, setShowPayment] = useState(false)
  const [checkoutLoading, setCheckoutLoading] = useState(false)
  const [checkoutError, setCheckoutError] = useState('')
  const [clientSecret, setClientSecret] = useState(null)
  const [orderId, setOrderId] = useState(null)
  const [paymentId, setPaymentId] = useState(null)
  const [success, setSuccess] = useState(false)
  const [paymentIntent, setPaymentIntent] = useState(null)
  const [selectedProduct, setSelectedProduct] = useState(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [loadingProduct, setLoadingProduct] = useState(false)
  const paymentFormRef = useRef(null)
  const [deliveryInfo, setDeliveryInfo] = useState({
    fullName: '',
    address: '',
    city: '',
    province: '',
    postalCode: '',
    country: 'Canada',
    phone: '',
  })
  const [deliveryErrors, setDeliveryErrors] = useState({})
  const [updatingDeliveryDetails, setUpdatingDeliveryDetails] = useState(false)
  const [deliveryUpdateError, setDeliveryUpdateError] = useState('')
  const deliveryUpdateTimeoutRef = useRef(null)
  const initialDeliveryInfoRef = useRef(null)
  const deliveryInfoRef = useRef(deliveryInfo)

  // Keep ref in sync with state
  useEffect(() => {
    deliveryInfoRef.current = deliveryInfo
  }, [deliveryInfo])

  const handleDeliveryInfoChange = (e) => {
    const { name, value } = e.target
    const updatedInfo = {
      ...deliveryInfo,
      [name]: value,
    }
    setDeliveryInfo(updatedInfo)
    // Update ref immediately so debounced function has latest value
    deliveryInfoRef.current = updatedInfo
    
    // Clear error for this field when user starts typing
    if (deliveryErrors[name]) {
      setDeliveryErrors((prev) => {
        const newErrors = { ...prev }
        delete newErrors[name]
        return newErrors
      })
    }
    
    // If order is already created and payment form is shown, update delivery details
    if (showPayment && orderId) {
      // Clear existing timeout
      if (deliveryUpdateTimeoutRef.current) {
        clearTimeout(deliveryUpdateTimeoutRef.current)
      }
      
      // Debounce the API call (wait 1 second after user stops typing)
      deliveryUpdateTimeoutRef.current = setTimeout(async () => {
        await updateDeliveryDetailsInOrder()
      }, 1000)
    }
  }

  const updateDeliveryDetailsInOrder = async () => {
    if (!orderId || !showPayment) return
    
    // Use ref to get the latest deliveryInfo value (avoids closure stale value issue)
    const currentDeliveryInfo = deliveryInfoRef.current
    
    // Validate delivery details before updating
    const errors = {}
    if (!currentDeliveryInfo.fullName || currentDeliveryInfo.fullName.trim() === '') {
      errors.fullName = 'Full name is required'
    }
    if (!currentDeliveryInfo.address || currentDeliveryInfo.address.trim() === '') {
      errors.address = 'Address is required'
    }
    if (!currentDeliveryInfo.city || currentDeliveryInfo.city.trim() === '') {
      errors.city = 'City is required'
    }
    if (!currentDeliveryInfo.province || currentDeliveryInfo.province.trim() === '') {
      errors.province = 'Province/State is required'
    }
    if (!currentDeliveryInfo.postalCode || currentDeliveryInfo.postalCode.trim() === '') {
      errors.postalCode = 'Postal code is required'
    }
    if (!currentDeliveryInfo.country || currentDeliveryInfo.country.trim() === '') {
      errors.country = 'Country is required'
    }
    
    if (Object.keys(errors).length > 0) {
      setDeliveryErrors(errors)
      setDeliveryUpdateError('Please fill in all required delivery information')
      return
    }
    
    setUpdatingDeliveryDetails(true)
    setDeliveryUpdateError('')
    
    try {
      const token = await getAccessToken()
      if (!token) {
        throw new Error('No access token available. Please log in.')
      }
      
      // Use ref value to ensure we have the latest data
      await orderApiClient.updateDeliveryDetails(orderId, currentDeliveryInfo, token)
      // Update the initial reference to the new values
      initialDeliveryInfoRef.current = { ...currentDeliveryInfo }
    } catch (err) {
      console.error('Failed to update delivery details:', err)
      setDeliveryUpdateError(err.message || 'Failed to update delivery details')
    } finally {
      setUpdatingDeliveryDetails(false)
    }
  }
  
  // Cleanup timeout on unmount
  useEffect(() => {
    return () => {
      if (deliveryUpdateTimeoutRef.current) {
        clearTimeout(deliveryUpdateTimeoutRef.current)
      }
    }
  }, [])

  const handleAutoFillDelivery = () => {
    setDeliveryInfo({
      fullName: 'John Doe',
      address: '123 Main Street',
      city: 'Toronto',
      province: 'Ontario',
      postalCode: 'M5H 2N2',
      country: 'Canada',
      phone: '416-555-1234',
    })
  }

  const formatPrice = (cents, currency = 'CAD') => {
    return new Intl.NumberFormat('en-CA', {
      style: 'currency',
      currency: currency,
    }).format(cents / 100)
  }

  const truncateProductName = (name, maxLength = 50) => {
    if (!name) return name
    if (name.length <= maxLength) return name
    return name.substring(0, maxLength).trim() + '...'
  }

  const totalItems = getTotalItems()
  const totalPrice = getTotalPrice()
  const currency = cartItems.length > 0 && cartItems[0].currency ? cartItems[0].currency : 'CAD'

  const validateDeliveryInfo = () => {
    const errors = {}
    
    if (!deliveryInfo.fullName || deliveryInfo.fullName.trim() === '') {
      errors.fullName = 'Full name is required'
    }
    if (!deliveryInfo.address || deliveryInfo.address.trim() === '') {
      errors.address = 'Address is required'
    }
    if (!deliveryInfo.city || deliveryInfo.city.trim() === '') {
      errors.city = 'City is required'
    }
    if (!deliveryInfo.province || deliveryInfo.province.trim() === '') {
      errors.province = 'Province/State is required'
    }
    if (!deliveryInfo.postalCode || deliveryInfo.postalCode.trim() === '') {
      errors.postalCode = 'Postal code is required'
    }
    if (!deliveryInfo.country || deliveryInfo.country.trim() === '') {
      errors.country = 'Country is required'
    }
    // Phone is optional, so no validation needed
    
    setDeliveryErrors(errors)
    return Object.keys(errors).length === 0
  }

  const handleProceedToPayment = async () => {
    if (cartItems.length === 0) {
      setCheckoutError('Your cart is empty')
      return
    }

    // Validate delivery details before proceeding
    if (!validateDeliveryInfo()) {
      setCheckoutError('Please fill in all required delivery information')
      return
    }

    setCheckoutLoading(true)
    setCheckoutError('')

    try {
      const token = await getAccessToken()
      if (!token) {
        throw new Error('No access token available. Please log in.')
      }

      // Prepare delivery details - all fields are required except phone
      const deliveryDetails = {
        full_name: deliveryInfo.fullName.trim(),
        address: deliveryInfo.address.trim(),
        city: deliveryInfo.city.trim(),
        province: deliveryInfo.province.trim(),
        postal_code: deliveryInfo.postalCode.trim(),
        country: deliveryInfo.country.trim(),
        phone: deliveryInfo.phone.trim() || '', // Phone is optional, send empty string if not provided
      }

      const checkoutResponse = await cartApiClient.checkout(token, deliveryDetails)

      if (checkoutResponse.error) {
        throw new Error(checkoutResponse.error)
      }

      if (!checkoutResponse.clientSecret) {
        throw new Error('No client secret received from checkout')
      }

      setClientSecret(checkoutResponse.clientSecret)
      setOrderId(checkoutResponse.orderId)
      setPaymentId(checkoutResponse.paymentId)
      setShowPayment(true)
      
      // Store initial delivery info for comparison
      initialDeliveryInfoRef.current = { ...deliveryInfo }
      
      // Scroll to payment form after a short delay to allow it to render
      setTimeout(() => {
        if (paymentFormRef.current) {
          paymentFormRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }
      }, 100)
    } catch (err) {
      setCheckoutError(err.message || 'Failed to initiate checkout')
    } finally {
      setCheckoutLoading(false)
    }
  }

  const handlePaymentSuccess = async (intent, orderId, paymentId) => {
    setPaymentIntent(intent)
    setSuccess(true)
    // Clear the cart after successful payment
    try {
      await clearCart()
    } catch (err) {
      console.error('Failed to clear cart after payment:', err)
      // Don't block the success UI if cart clearing fails
    }
  }

  const handleBackToCart = () => {
    navigate('/')
  }

  const handleProductClick = async (productId, event) => {
    // Don't open modal if clicking on quantity controls or delete button
    if (event.target.closest('button') || event.target.closest('[role="button"]')) {
      return
    }

    setLoadingProduct(true)
    try {
      const product = await catalogApiClient.getProduct(productId)
      setSelectedProduct(product)
      setModalOpen(true)
    } catch (err) {
      console.error('Error fetching product details:', err)
    } finally {
      setLoadingProduct(false)
    }
  }

  const handleCloseModal = () => {
    setModalOpen(false)
    setSelectedProduct(null)
  }

  if (success) {
    return (
      <Card>
        <CardContent sx={{ textAlign: 'center', py: 6 }}>
          <CheckCircleIcon sx={{ fontSize: 64, color: 'success.main', mb: 2 }} />
          <Typography variant="h4" color="success.main" gutterBottom>
            Payment Successful!
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ mb: 3 }}>
            Your order has been confirmed and payment has been processed successfully.
          </Typography>
          {orderId && (
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              Order ID: {orderId}
            </Typography>
          )}
          {paymentId && (
            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
              Payment ID: {paymentId}
            </Typography>
          )}
          <Button onClick={handleBackToCart} variant="contained" size="large" sx={{ mr: 2 }}>
            Continue Shopping
          </Button>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardContent>
        <Typography
          variant="h4"
          component="h2"
          gutterBottom
          sx={{
            fontWeight: 700,
            background: 'linear-gradient(135deg, #14b8a6 0%, #0d9488 100%)',
            backgroundClip: 'text',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
            mb: 3,
          }}
        >
          Checkout
        </Typography>

        {checkoutError && (
          <Alert severity="error" sx={{ mb: 3 }} onClose={() => setCheckoutError('')}>
            {checkoutError}
          </Alert>
        )}

        {/* Cart Items Section */}
        <Card variant="outlined" sx={{ mb: 3 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Review Your Order ({totalItems} {totalItems === 1 ? 'item' : 'items'})
            </Typography>

            {cartItems.length === 0 ? (
              <Box sx={{ p: 4, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">
                  {cartLoading ? 'Loading cart...' : 'Your cart is empty'}
                </Typography>
                <Button variant="contained" sx={{ mt: 2 }} onClick={handleBackToCart}>
                  Continue Shopping
                </Button>
              </Box>
            ) : (
              <>
                <List sx={{ p: 0 }}>
                  {cartItems.map((item, index) => (
                    <Box key={item.productId || item.itemId}>
                      <ListItem
                        sx={{
                          py: 2,
                          px: 0,
                          cursor: 'pointer',
                          '&:hover': {
                            backgroundColor: 'action.hover',
                          },
                        }}
                        onClick={(e) => handleProductClick(item.productId, e)}
                      >
                        {item.productImage && (
                          <Box
                            component="img"
                            src={item.productImage}
                            alt={item.productName}
                            sx={{
                              width: 60,
                              height: 60,
                              objectFit: 'cover',
                              borderRadius: 1,
                              mr: 2,
                            }}
                            onError={(e) => {
                              e.target.style.display = 'none'
                            }}
                          />
                        )}
                        <ListItemText
                          primary={
                            <Typography 
                              variant="body1" 
                              sx={{ 
                                fontWeight: 'medium',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis',
                                display: '-webkit-box',
                                WebkitLineClamp: 2,
                                WebkitBoxOrient: 'vertical',
                                lineHeight: 1.4,
                              }}
                              title={item.productName || `Product ${item.sku || item.productId}`}
                            >
                              {truncateProductName(item.productName || `Product ${item.sku || item.productId}`, 60)}
                            </Typography>
                          }
                          secondary={
                            <Box>
                              <Typography variant="caption" color="text.secondary">
                                {formatPrice(item.priceCents, item.currency)} each
                              </Typography>
                              <Box
                                sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1 }}
                                onClick={(e) => e.stopPropagation()}
                              >
                                <IconButton
                                  size="small"
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    updateQuantity(item.productId, item.quantity - 1)
                                  }}
                                  disabled={cartLoading || showPayment}
                                >
                                  <RemoveIcon fontSize="small" />
                                </IconButton>
                                <Typography variant="body2" sx={{ minWidth: 30, textAlign: 'center' }}>
                                  {item.quantity}
                                </Typography>
                                <IconButton
                                  size="small"
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    updateQuantity(item.productId, item.quantity + 1)
                                  }}
                                  disabled={cartLoading || showPayment}
                                >
                                  <AddIcon fontSize="small" />
                                </IconButton>
                              </Box>
                            </Box>
                          }
                        />
                        <ListItemSecondaryAction onClick={(e) => e.stopPropagation()}>
                          <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 1 }}>
                            <Typography variant="body1" sx={{ fontWeight: 'medium' }}>
                              {formatPrice(item.priceCents * item.quantity, item.currency)}
                            </Typography>
                            <IconButton
                              edge="end"
                              size="small"
                              onClick={(e) => {
                                e.stopPropagation()
                                removeFromCart(item.productId)
                              }}
                              disabled={cartLoading || showPayment}
                              color="error"
                            >
                              <DeleteIcon fontSize="small" />
                            </IconButton>
                          </Box>
                        </ListItemSecondaryAction>
                      </ListItem>
                      {index < cartItems.length - 1 && <Divider />}
                    </Box>
                  ))}
                </List>

                <Divider sx={{ my: 2 }} />

                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                  <Typography variant="h6">Total</Typography>
                  <Typography variant="h6" sx={{ fontWeight: 'bold' }}>
                    {formatPrice(totalPrice, currency)}
                  </Typography>
                </Box>
              </>
            )}
          </CardContent>
        </Card>

        {/* Delivery Information Section - Always visible when cart has items */}
        {cartItems.length > 0 && (
          <Card variant="outlined" sx={{ mb: 3 }}>
            <CardContent>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                <Typography variant="h6" gutterBottom>
                  Delivery Information
                </Typography>
                {!IS_PRODUCTION && (
                  <Button
                    variant="outlined"
                    size="small"
                    onClick={handleAutoFillDelivery}
                    sx={{ ml: 2 }}
                  >
                    Auto-fill (Dev)
                  </Button>
                )}
              </Box>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
                Please provide your delivery address. This information will be saved with your order.
              </Typography>
              
              {showPayment && (
                <Alert severity="info" sx={{ mb: 2 }}>
                  You can still edit your delivery details. Changes will be saved automatically.
                </Alert>
              )}
              
              {updatingDeliveryDetails && (
                <Alert severity="info" sx={{ mb: 2 }}>
                  Updating delivery details...
                </Alert>
              )}
              
              {deliveryUpdateError && (
                <Alert severity="error" sx={{ mb: 2 }}>
                  {deliveryUpdateError}
                </Alert>
              )}

              <Grid container spacing={2}>
                <Grid item xs={12}>
                  <TextField
                    fullWidth
                    required
                    label="Full Name"
                    name="fullName"
                    value={deliveryInfo.fullName}
                    onChange={handleDeliveryInfoChange}
                    error={!!deliveryErrors.fullName}
                    helperText={deliveryErrors.fullName}
                  />
                </Grid>
                <Grid item xs={12}>
                  <TextField
                    fullWidth
                    required
                    label="Address"
                    name="address"
                    value={deliveryInfo.address}
                    onChange={handleDeliveryInfoChange}
                    error={!!deliveryErrors.address}
                    helperText={deliveryErrors.address}
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    required
                    label="City"
                    name="city"
                    value={deliveryInfo.city}
                    onChange={handleDeliveryInfoChange}
                    error={!!deliveryErrors.city}
                    helperText={deliveryErrors.city}
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    required
                    label="Province/State"
                    name="province"
                    value={deliveryInfo.province}
                    onChange={handleDeliveryInfoChange}
                    error={!!deliveryErrors.province}
                    helperText={deliveryErrors.province}
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    required
                    label="Postal Code"
                    name="postalCode"
                    value={deliveryInfo.postalCode}
                    onChange={handleDeliveryInfoChange}
                    error={!!deliveryErrors.postalCode}
                    helperText={deliveryErrors.postalCode}
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    required
                    label="Country"
                    name="country"
                    value={deliveryInfo.country}
                    onChange={handleDeliveryInfoChange}
                    error={!!deliveryErrors.country}
                    helperText={deliveryErrors.country}
                  />
                </Grid>
                <Grid item xs={12}>
                  <TextField
                    fullWidth
                    label="Phone (optional)"
                    name="phone"
                    value={deliveryInfo.phone}
                    onChange={handleDeliveryInfoChange}
                  />
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        )}

        {/* Proceed to Payment Button */}
        {!showPayment && cartItems.length > 0 && (
          <Button
            variant="contained"
            fullWidth
            size="large"
            onClick={handleProceedToPayment}
            disabled={checkoutLoading || cartLoading}
            sx={{ py: 1.5, mb: 3 }}
          >
            {checkoutLoading ? (
              <>
                <CircularProgress size={20} color="inherit" sx={{ mr: 1 }} />
                Processing...
              </>
            ) : (
              'Proceed to Payment'
            )}
          </Button>
        )}

        {/* Payment Form - Only shown after checkout is initiated */}
        <div ref={paymentFormRef}>
          <Collapse in={showPayment}>
            {clientSecret && (
              <Elements stripe={stripePromise}>
                <PaymentForm
                  buyerId={buyerId}
                  clientSecret={clientSecret}
                  orderId={orderId}
                  paymentId={paymentId}
                  onSuccess={handlePaymentSuccess}
                />
              </Elements>
            )}
          </Collapse>
        </div>
      </CardContent>
      <ProductModal open={modalOpen} onClose={handleCloseModal} product={selectedProduct} />
    </Card>
  )
}

function Checkout({ buyerId }) {
  return (
    <Container maxWidth="md" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <CheckoutForm buyerId={buyerId} />
    </Container>
  )
}

export default Checkout
