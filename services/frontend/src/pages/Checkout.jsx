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
  Select,
  MenuItem,
  FormControl,
  InputLabel,
  Chip,
  IconButton,
  Snackbar,
} from '@mui/material'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import CloseIcon from '@mui/icons-material/Close'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import paymentsApi from '../api/paymentsApi'

const stripePromise = loadStripe(import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY || 'missing_stripe_publishable_key')

function CheckoutForm({ buyerId }) {
  const stripe = useStripe()
  const elements = useElements()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [paymentId, setPaymentId] = useState(null)
  const [copySuccess, setCopySuccess] = useState({})
  
  // Sanitization and validation utilities (defined before useState to use in initial state)
  const sanitizeString = (str, maxLength = 1000) => {
    if (typeof str !== 'string') return ''
    // Trim whitespace
    let sanitized = str.trim()
    // Remove null bytes and control characters (except newlines and tabs for description)
    sanitized = sanitized.replace(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/g, '')
    // Limit length
    if (sanitized.length > maxLength) {
      sanitized = sanitized.substring(0, maxLength)
    }
    return sanitized
  }

  const sanitizeSellerId = (sellerId) => {
    // Seller ID is expected to be an email address
    let sanitized = sanitizeString(sellerId, 255) // Email max length is 255
    // Remove null bytes and control characters, but keep email-valid characters
    // Allow: letters, numbers, @, ., -, _, + (for email addresses)
    sanitized = sanitized.replace(/[^\w@.+_-]/g, '')
    return sanitized
  }

  const validateEmail = (email) => {
    // Basic email validation regex
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    return emailRegex.test(email)
  }

  const sanitizeDescription = (description) => {
    // Description can have more characters, allow letters, numbers, spaces, basic punctuation
    let sanitized = sanitizeString(description, 500)
    // Remove potentially dangerous characters but keep basic punctuation
    sanitized = sanitized.replace(/[<>{}[\]\\]/g, '')
    return sanitized
  }

  const validateAmount = (amount) => {
    // Must be a positive integer
    const num = parseInt(amount)
    if (isNaN(num) || num < 1) {
      return { valid: false, value: 0 }
    }
    // Cap at reasonable maximum (1 million dollars = 100,000,000 cents)
    const maxCents = 100000000
    const validAmount = Math.min(num, maxCents)
    return { valid: true, value: validAmount }
  }

  const validateCurrency = (currency) => {
    // Only allow CAD for now (as per recent changes)
    const allowedCurrencies = ['CAD']
    return allowedCurrencies.includes(currency) ? currency : 'CAD'
  }

  const [formData, setFormData] = useState({
    sellerId: sanitizeSellerId('seller@example.com'),
    grossAmountCents: 10000,
    currency: validateCurrency('CAD'),
    description: sanitizeDescription('Test payment from BuyIt frontend')
  })

  const testCardPresets = [
    { id: 'chargeback', name: 'Chargeback', cardNumber: '4000 0000 0000 0259', expiry: '12/28', cvc: '123', zip: '12345', description: 'Payment succeeds, then triggers a chargeback/dispute' },
    { id: 'success', name: 'Success', cardNumber: '4242 4242 4242 4242', expiry: '12/28', cvc: '123', zip: '12345', description: 'Payment succeeds' },
    { id: 'decline', name: 'Decline', cardNumber: '4000 0000 0000 0002', expiry: '12/28', cvc: '123', zip: '12345', description: 'Card is declined' },
    { id: 'insufficient_funds', name: 'Insufficient Funds', cardNumber: '4000 0000 0000 9995', expiry: '12/28', cvc: '123', zip: '12345', description: 'Insufficient funds' },
    { id: 'lost_card', name: 'Lost Card', cardNumber: '4000 0000 0000 9987', expiry: '12/28', cvc: '123', zip: '12345', description: 'Lost card' },
    { id: 'stolen_card', name: 'Stolen Card', cardNumber: '4000 0000 0000 9979', expiry: '12/28', cvc: '123', zip: '12345', description: 'Stolen card' },
    { id: 'requires_authentication', name: '3D Secure', cardNumber: '4000 0025 0000 3155', expiry: '12/28', cvc: '123', zip: '12345', description: 'Requires 3D Secure authentication' },
    { id: 'requires_payment_method', name: 'Requires Payment Method', cardNumber: '4000 0000 0000 0341', expiry: '12/28', cvc: '123', zip: '12345', description: 'Requires payment method' },
    { id: 'processing_error', name: 'Processing Error', cardNumber: '4000 0000 0000 0119', expiry: '12/28', cvc: '123', zip: '12345', description: 'Processing error' },
  ]
  
  const [selectedTestCard, setSelectedTestCard] = useState(null)

  const copyToClipboard = async (text, field) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopySuccess({ [field]: true })
      setTimeout(() => setCopySuccess({ ...copySuccess, [field]: false }), 2000)
    } catch (err) {
      console.error('Failed to copy:', err)
    }
  }

  const handleInputChange = (e) => {
    const { name, value } = e.target
    let sanitizedValue = value

    if (name === 'sellerId') {
      sanitizedValue = sanitizeSellerId(value)
    } else if (name === 'grossAmountCents') {
      const validation = validateAmount(value)
      sanitizedValue = validation.value
      if (!validation.valid && value !== '') {
        // Show error for invalid amount
        setError('Amount must be a positive number')
        return
      }
    } else if (name === 'currency') {
      sanitizedValue = validateCurrency(value)
    } else if (name === 'description') {
      sanitizedValue = sanitizeDescription(value)
    }

    setFormData(prev => ({
      ...prev,
      [name]: name === 'grossAmountCents' ? sanitizedValue : sanitizedValue
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

    try {
      // Sanitize and validate all fields before submission
      const sanitizedSellerId = sanitizeSellerId(formData.sellerId)
      const amountValidation = validateAmount(formData.grossAmountCents)
      const sanitizedCurrency = validateCurrency(formData.currency)
      const sanitizedDescription = sanitizeDescription(formData.description || '')

      // Validate required fields
      if (!sanitizedSellerId || sanitizedSellerId.length === 0) {
        setError('Seller email is required')
        setLoading(false)
        return
      }

      // Validate email format
      if (!validateEmail(sanitizedSellerId)) {
        setError('Seller email must be a valid email address')
        setLoading(false)
        return
      }

      if (!amountValidation.valid || amountValidation.value < 1) {
        setError('Amount must be a positive number')
        setLoading(false)
        return
      }

      if (!sanitizedCurrency) {
        setError('Currency is required')
        setLoading(false)
        return
      }

      // buyerId is now extracted from the authenticated user's JWT token on the backend
      const paymentResponse = await paymentsApi.createPayment({
        sellerId: sanitizedSellerId,
        grossAmountCents: amountValidation.value,
        currency: sanitizedCurrency,
        description: sanitizedDescription
      })

      if (paymentResponse.error) {
        throw new Error(paymentResponse.error)
      }

      if (!paymentResponse.clientSecret) {
        throw new Error('No client secret received from payment creation')
      }

      setPaymentId(paymentResponse.id)

      const cardElement = elements.getElement(CardElement)
      if (!cardElement) {
        throw new Error('Card element not found')
      }

      const { error: confirmError, paymentIntent } = await stripe.confirmCardPayment(
        paymentResponse.clientSecret,
        {
          payment_method: {
            card: cardElement,
            billing_details: {
              name: buyerId,
            },
          },
        }
      )

      if (confirmError) {
        setError(confirmError.message || 'Payment failed')
        setLoading(false)
        return
      }

      if (paymentIntent.status === 'succeeded' || paymentIntent.status === 'requires_capture') {
        setSuccess(true)
      } else {
        setError(`Payment status: ${paymentIntent.status}`)
      }
    } catch (err) {
      setError(err.message || 'An error occurred during payment')
    } finally {
      setLoading(false)
    }
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
            Your payment has been processed successfully.
          </Typography>
          {paymentId && (
            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
              Payment ID: {paymentId}
            </Typography>
          )}
          <Button
            onClick={() => {
              setSuccess(false)
              setPaymentId(null)
              setError('')
              setSelectedTestCard(null)
            }}
            variant="contained"
            size="large"
          >
            Make Another Payment
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
        
        {error && (
          <Alert severity="error" sx={{ mb: 3 }}>
            {error}
          </Alert>
        )}
        
        <form onSubmit={handleSubmit}>
          <TextField
            fullWidth
            label="Seller Email"
            name="sellerId"
            type="email"
            value={formData.sellerId}
            onChange={handleInputChange}
            required
            margin="normal"
            inputProps={{
              maxLength: 255,
            }}
            helperText="Enter the seller's email address"
          />

          <TextField
            fullWidth
            label="Amount (cents)"
            name="grossAmountCents"
            type="number"
            value={formData.grossAmountCents}
            onChange={handleInputChange}
            min="1"
            max="100000000"
            required
            margin="normal"
            inputProps={{
              step: 1,
            }}
            helperText={`$${(formData.grossAmountCents / 100).toFixed(2)} ${formData.currency} (Max: $1,000,000.00)`}
          />

          <FormControl fullWidth margin="normal">
            <InputLabel>Currency</InputLabel>
            <Select
              name="currency"
              value={formData.currency}
              onChange={handleInputChange}
              label="Currency"
            >
              <MenuItem value="CAD">CAD</MenuItem>
              <MenuItem value="EUR" disabled>EUR (Coming soon)</MenuItem>
              <MenuItem value="GBP" disabled>GBP (Coming soon)</MenuItem>
              <MenuItem value="USD" disabled>USD (Coming soon)</MenuItem>
            </Select>
          </FormControl>

          <TextField
            fullWidth
            label="Description"
            name="description"
            value={formData.description}
            onChange={handleInputChange}
            multiline
            rows={3}
            margin="normal"
            inputProps={{
              maxLength: 500,
            }}
            helperText={`${formData.description?.length || 0}/500 characters`}
          />

          <Box sx={{ mt: 3, mb: 2 }}>
            <Typography variant="subtitle2" gutterBottom>
              Test Card Presets
            </Typography>
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mb: 2 }}>
              {testCardPresets.map((preset) => (
                <Chip
                  key={preset.id}
                  label={preset.name}
                  onClick={() => setSelectedTestCard(preset)}
                  color={selectedTestCard?.id === preset.id ? 'primary' : 'default'}
                  variant={selectedTestCard?.id === preset.id ? 'filled' : 'outlined'}
                />
              ))}
            </Box>
            
            {selectedTestCard && (
              <Alert
                severity="info"
                action={
                  <IconButton
                    size="small"
                    onClick={() => setSelectedTestCard(null)}
                  >
                    <CloseIcon fontSize="small" />
                  </IconButton>
                }
                sx={{ mb: 2 }}
              >
                <Typography variant="subtitle2" gutterBottom>
                  {selectedTestCard.name}
                </Typography>
                <Typography variant="caption" display="block">
                  {selectedTestCard.description}
                </Typography>
                <Typography variant="caption" display="block" sx={{ mt: 1 }}>
                  Copy and paste these details into the Stripe card element below:
                </Typography>
              </Alert>
            )}

            {selectedTestCard && (
              <Box sx={{ 
                p: 2, 
                bgcolor: 'grey.50', 
                borderRadius: 1, 
                border: '1px solid',
                borderColor: 'divider',
                mb: 2
              }}>
                <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)' }, gap: 2 }}>
                  {[
                    { label: 'Card Number', value: selectedTestCard.cardNumber, field: 'card' },
                    { label: 'Expiry', value: selectedTestCard.expiry, field: 'expiry' },
                    { label: 'CVC', value: selectedTestCard.cvc, field: 'cvc' },
                    { label: 'ZIP Code', value: selectedTestCard.zip, field: 'zip' },
                  ].map(({ label, value, field }) => (
                    <Box key={field}>
                      <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.5 }}>
                        {label}
                      </Typography>
                      <Box sx={{ display: 'flex', gap: 1 }}>
                        <TextField
                          value={value}
                          size="small"
                          fullWidth
                          InputProps={{
                            readOnly: true,
                            sx: { fontFamily: 'monospace', fontSize: '0.875rem' }
                          }}
                        />
                        <Button
                          size="small"
                          variant="outlined"
                          startIcon={<ContentCopyIcon />}
                          onClick={() => copyToClipboard(field === 'card' ? value.replace(/\s/g, '') : value, field)}
                          color={copySuccess[field] ? 'success' : 'primary'}
                        >
                          {copySuccess[field] ? 'Copied!' : 'Copy'}
                        </Button>
                      </Box>
                    </Box>
                  ))}
                </Box>
              </Box>
            )}
          </Box>

          <Box sx={{ mb: 3 }}>
            <Typography variant="subtitle2" gutterBottom>
              Card Details
            </Typography>
            <Box sx={{ 
              p: 2, 
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: 1,
              bgcolor: 'background.paper'
            }}>
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
          >
            {loading ? 'Processing...' : `Pay $${(formData.grossAmountCents / 100).toFixed(2)}`}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}

function Checkout({ buyerId }) {
  return (
    <Container maxWidth="md" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <Elements stripe={stripePromise}>
        <CheckoutForm buyerId={buyerId} />
      </Elements>
    </Container>
  )
}

export default Checkout
