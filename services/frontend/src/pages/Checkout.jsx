import { useState } from 'react'
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
import { useNavigate } from 'react-router-dom'
import ProductModal from '../components/ProductModal'
import { catalogApiClient } from '../api/catalogApi'

const stripePromise = loadStripe(import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY || 'missing_stripe_publishable_key')

function PaymentForm({ buyerId, clientSecret, orderId, paymentId, onSuccess }) {
  const stripe = useStripe()
  const elements = useElements()
  const { user } = useAuth0()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [deliveryInfo, setDeliveryInfo] = useState({
    fullName: '',
    address: '',
    city: '',
    province: '',
    postalCode: '',
    country: 'Canada',
    phone: '',
  })

  const handleDeliveryInfoChange = (e) => {
    const { name, value } = e.target
    setDeliveryInfo((prev) => ({
      ...prev,
      [name]: value,
    }))
  }

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
        <Typography variant="h6" gutterBottom sx={{ mb: 3 }}>
          Delivery Information
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          This information is not persisted and is optional. It will be used for billing details only.
        </Typography>

        <Grid container spacing={2} sx={{ mb: 3 }}>
          <Grid item xs={12}>
            <TextField
              fullWidth
              label="Full Name"
              name="fullName"
              value={deliveryInfo.fullName}
              onChange={handleDeliveryInfoChange}
              margin="normal"
            />
          </Grid>
          <Grid item xs={12}>
            <TextField
              fullWidth
              label="Address"
              name="address"
              value={deliveryInfo.address}
              onChange={handleDeliveryInfoChange}
              margin="normal"
            />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField
              fullWidth
              label="City"
              name="city"
              value={deliveryInfo.city}
              onChange={handleDeliveryInfoChange}
              margin="normal"
            />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField
              fullWidth
              label="Province/State"
              name="province"
              value={deliveryInfo.province}
              onChange={handleDeliveryInfoChange}
              margin="normal"
            />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField
              fullWidth
              label="Postal Code"
              name="postalCode"
              value={deliveryInfo.postalCode}
              onChange={handleDeliveryInfoChange}
              margin="normal"
            />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField
              fullWidth
              label="Country"
              name="country"
              value={deliveryInfo.country}
              onChange={handleDeliveryInfoChange}
              margin="normal"
            />
          </Grid>
          <Grid item xs={12}>
            <TextField
              fullWidth
              label="Phone (optional)"
              name="phone"
              value={deliveryInfo.phone}
              onChange={handleDeliveryInfoChange}
              margin="normal"
            />
          </Grid>
        </Grid>

        <Divider sx={{ my: 3 }} />

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

  const handleProceedToPayment = async () => {
    if (cartItems.length === 0) {
      setCheckoutError('Your cart is empty')
      return
    }

    setCheckoutLoading(true)
    setCheckoutError('')

    try {
      const token = await getAccessToken()
      if (!token) {
        throw new Error('No access token available. Please log in.')
      }

      const checkoutResponse = await cartApiClient.checkout(token)

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
