import { useState, useEffect } from 'react'
import { loadStripe } from '@stripe/stripe-js'
import paymentsApi from '../api/paymentsApi'
import '../App.css'
import './Checkout.css'

// Initialize Stripe - using test publishable key
// In production, this should come from environment variables
const stripePromise = loadStripe(import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY || 'pk_test_51SmG1S2SQYvDNXZz5bkKmq1XENOih8R6WUPY9US0l2OXJr0HrkFTpMqhjy4Uo5cxMTT0bSpGmtrlaSPDk14RNgRC00Em4yYJ4q')

function CheckoutForm({ buyerId, onPaymentSuccess }) {
  const [stripe, setStripe] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [paymentId, setPaymentId] = useState(null)
  
  const [formData, setFormData] = useState({
    sellerId: 'seller_buyittestseller',
    grossAmountCents: 10000, // $100.00
    currency: 'USD',
    description: 'Test payment from BuyIt frontend'
  })
  
  const [cardData, setCardData] = useState({
    cardNumber: '',
    expiry: '',
    cvc: '',
    zip: ''
  })
  
  const [selectedTestCard, setSelectedTestCard] = useState(null)

  // Initialize Stripe
  useEffect(() => {
    stripePromise.then(setStripe)
  }, [])

  // Test card presets based on Stripe test cards
  const testCardPresets = [
    {
      id: 'success',
      name: 'Success',
      cardNumber: '4242 4242 4242 4242',
      expiry: '12/28',
      cvc: '123',
      zip: '12345',
      description: 'Payment succeeds'
    },
    {
      id: 'decline',
      name: 'Decline',
      cardNumber: '4000 0000 0000 0002',
      expiry: '12/28',
      cvc: '123',
      zip: '12345',
      description: 'Card is declined'
    },
    {
      id: 'insufficient_funds',
      name: 'Insufficient Funds',
      cardNumber: '4000 0000 0000 9995',
      expiry: '12/28',
      cvc: '123',
      zip: '12345',
      description: 'Insufficient funds'
    },
    {
      id: 'lost_card',
      name: 'Lost Card',
      cardNumber: '4000 0000 0000 9987',
      expiry: '12/28',
      cvc: '123',
      zip: '12345',
      description: 'Lost card'
    },
    {
      id: 'stolen_card',
      name: 'Stolen Card',
      cardNumber: '4000 0000 0000 9979',
      expiry: '12/28',
      cvc: '123',
      zip: '12345',
      description: 'Stolen card'
    },
    {
      id: 'requires_authentication',
      name: '3D Secure',
      cardNumber: '4000 0025 0000 3155',
      expiry: '12/28',
      cvc: '123',
      zip: '12345',
      description: 'Requires 3D Secure authentication'
    },
    {
      id: 'requires_payment_method',
      name: 'Requires Payment Method',
      cardNumber: '4000 0000 0000 0341',
      expiry: '12/28',
      cvc: '123',
      zip: '12345',
      description: 'Requires payment method'
    },
    {
      id: 'processing_error',
      name: 'Processing Error',
      cardNumber: '4000 0000 0000 0119',
      expiry: '12/28',
      cvc: '123',
      zip: '12345',
      description: 'Processing error'
    }
  ]

  const handleTestCardSelect = (preset) => {
    setSelectedTestCard(preset)
    // Auto-fill the card form fields
    setCardData({
      cardNumber: preset.cardNumber.replace(/\s/g, ''),
      expiry: preset.expiry,
      cvc: preset.cvc,
      zip: preset.zip
    })
  }

  const handleCardInputChange = (e) => {
    const { name, value } = e.target
    let formattedValue = value
    
    // Format card number with spaces
    if (name === 'cardNumber') {
      formattedValue = value.replace(/\s/g, '').replace(/(.{4})/g, '$1 ').trim()
      if (formattedValue.length > 19) formattedValue = formattedValue.substring(0, 19)
    }
    // Format expiry as MM/YY
    else if (name === 'expiry') {
      formattedValue = value.replace(/\D/g, '')
      if (formattedValue.length >= 2) {
        formattedValue = formattedValue.substring(0, 2) + '/' + formattedValue.substring(2, 4)
      }
    }
    // Limit CVC to 3-4 digits
    else if (name === 'cvc') {
      formattedValue = value.replace(/\D/g, '').substring(0, 4)
    }
    // Limit ZIP to 5 digits
    else if (name === 'zip') {
      formattedValue = value.replace(/\D/g, '').substring(0, 5)
    }
    
    setCardData(prev => ({
      ...prev,
      [name]: formattedValue
    }))
  }

  const handleInputChange = (e) => {
    const { name, value } = e.target
    setFormData(prev => ({
      ...prev,
      [name]: name === 'grossAmountCents' ? parseInt(value) || 0 : value
    }))
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    if (!stripe) {
      setError('Stripe not loaded. Please wait...')
      setLoading(false)
      return
    }

    // Validate card fields
    if (!cardData.cardNumber || !cardData.expiry || !cardData.cvc || !cardData.zip) {
      setError('Please fill in all card details')
      setLoading(false)
      return
    }

    try {
      // Step 1: Create payment via API to get client_secret
      const paymentResponse = await paymentsApi.createPayment({
        buyerId: buyerId,
        sellerId: formData.sellerId,
        grossAmountCents: formData.grossAmountCents,
        currency: formData.currency,
        description: formData.description
      })

      if (paymentResponse.error) {
        throw new Error(paymentResponse.error)
      }

      if (!paymentResponse.clientSecret) {
        throw new Error('No client secret received from payment creation')
      }

      setPaymentId(paymentResponse.id)

      // Step 2: Parse expiry date
      const [expMonth, expYear] = cardData.expiry.split('/')
      if (!expMonth || !expYear) {
        throw new Error('Invalid expiry date format. Use MM/YY')
      }

      // Step 3: Create payment method with card details
      const { error: pmError, paymentMethod } = await stripe.createPaymentMethod({
        type: 'card',
        card: {
          number: cardData.cardNumber.replace(/\s/g, ''),
          exp_month: parseInt(expMonth),
          exp_year: parseInt('20' + expYear),
          cvc: cardData.cvc,
        },
        billing_details: {
          name: buyerId,
          address: {
            postal_code: cardData.zip,
          },
        },
      })

      if (pmError || !paymentMethod) {
        throw new Error(pmError?.message || 'Failed to create payment method')
      }

      // Step 4: Confirm payment with the payment method
      const { error: confirmError, paymentIntent } = await stripe.confirmCardPayment(
        paymentResponse.clientSecret,
        {
          payment_method: paymentMethod.id,
        }
      )

      if (confirmError) {
        setError(confirmError.message || 'Payment failed')
        setLoading(false)
        return
      }

      if (paymentIntent.status === 'succeeded' || paymentIntent.status === 'requires_capture') {
        setSuccess(true)
        // Store payment ID in localStorage for payments page
        const storedIds = JSON.parse(localStorage.getItem('paymentIds') || '[]')
        if (!storedIds.includes(paymentResponse.id)) {
          storedIds.push(paymentResponse.id)
          localStorage.setItem('paymentIds', JSON.stringify(storedIds))
        }
        if (onPaymentSuccess) {
          onPaymentSuccess(paymentResponse.id)
        }
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
      <div className="card">
        <div style={{ textAlign: 'center', padding: '2rem' }}>
          <div style={{ fontSize: '4rem', marginBottom: '1rem' }}>✅</div>
          <h2 style={{ color: '#28a745', marginBottom: '1rem' }}>Payment Successful!</h2>
          <p style={{ color: '#666', marginBottom: '2rem' }}>
            Your payment has been processed successfully.
          </p>
          {paymentId && (
            <p style={{ fontSize: '0.9rem', color: '#999' }}>
              Payment ID: {paymentId}
            </p>
          )}
          <button
            onClick={() => {
              setSuccess(false)
              setPaymentId(null)
              setError('')
              setCardData({ cardNumber: '', expiry: '', cvc: '', zip: '' })
              setSelectedTestCard(null)
            }}
            className="btn btn-primary"
            style={{ marginTop: '1rem' }}
          >
            Make Another Payment
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="card">
      <h2>Checkout</h2>
      
      {error && <div className="alert alert-error">{error}</div>}
      
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label htmlFor="sellerId">Seller ID</label>
          <input
            type="text"
            id="sellerId"
            name="sellerId"
            value={formData.sellerId}
            onChange={handleInputChange}
            required
          />
        </div>

        <div className="form-group">
          <label htmlFor="grossAmountCents">Amount (cents)</label>
          <input
            type="number"
            id="grossAmountCents"
            name="grossAmountCents"
            value={formData.grossAmountCents}
            onChange={handleInputChange}
            min="1"
            required
          />
          <small style={{ color: '#666', marginTop: '0.25rem', display: 'block' }}>
            ${(formData.grossAmountCents / 100).toFixed(2)} USD
          </small>
        </div>

        <div className="form-group">
          <label htmlFor="currency">Currency</label>
          <select
            id="currency"
            name="currency"
            value={formData.currency}
            onChange={handleInputChange}
            required
          >
            <option value="USD">USD</option>
            <option value="EUR">EUR</option>
            <option value="GBP">GBP</option>
          </select>
        </div>

        <div className="form-group">
          <label htmlFor="description">Description</label>
          <textarea
            id="description"
            name="description"
            value={formData.description}
            onChange={handleInputChange}
            rows="3"
          />
        </div>

        <div className="form-group">
          <label>Card Details</label>
          
          {/* Test Card Presets */}
          <div style={{ marginBottom: '1rem' }}>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', marginBottom: '0.5rem' }}>
              {testCardPresets.map((preset) => (
                <button
                  key={preset.id}
                  type="button"
                  onClick={() => handleTestCardSelect(preset)}
                  className={`btn ${selectedTestCard?.id === preset.id ? 'btn-primary' : 'btn-secondary'}`}
                  style={{ 
                    padding: '0.5rem 1rem', 
                    fontSize: '0.875rem',
                    whiteSpace: 'nowrap'
                  }}
                >
                  {preset.name}
                </button>
              ))}
            </div>
            
            {selectedTestCard && (
              <div className="alert alert-info" style={{ marginBottom: '1rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <strong>{selectedTestCard.name}</strong>
                    <div style={{ fontSize: '0.875rem', color: '#666', marginTop: '0.25rem' }}>
                      {selectedTestCard.description} - Card details have been auto-filled below
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setSelectedTestCard(null)
                      setCardData({ cardNumber: '', expiry: '', cvc: '', zip: '' })
                    }}
                    style={{ 
                      background: 'none', 
                      border: 'none', 
                      fontSize: '1.25rem', 
                      cursor: 'pointer',
                      color: '#666'
                    }}
                  >
                    ×
                  </button>
                </div>
              </div>
            )}
          </div>
          
          {/* Card Input Fields */}
          <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
            <div className="form-group" style={{ marginBottom: 0 }}>
              <label htmlFor="cardNumber">Card Number</label>
              <input
                type="text"
                id="cardNumber"
                name="cardNumber"
                value={cardData.cardNumber}
                onChange={handleCardInputChange}
                placeholder="4242 4242 4242 4242"
                maxLength="19"
                required
              />
            </div>
            <div className="form-group" style={{ marginBottom: 0 }}>
              <label htmlFor="expiry">Expiry (MM/YY)</label>
              <input
                type="text"
                id="expiry"
                name="expiry"
                value={cardData.expiry}
                onChange={handleCardInputChange}
                placeholder="12/28"
                maxLength="5"
                required
              />
            </div>
            <div className="form-group" style={{ marginBottom: 0 }}>
              <label htmlFor="cvc">CVC</label>
              <input
                type="text"
                id="cvc"
                name="cvc"
                value={cardData.cvc}
                onChange={handleCardInputChange}
                placeholder="123"
                maxLength="4"
                required
              />
            </div>
          </div>
          <div className="form-group" style={{ marginBottom: '1rem' }}>
            <label htmlFor="zip">ZIP Code</label>
            <input
              type="text"
              id="zip"
              name="zip"
              value={cardData.zip}
              onChange={handleCardInputChange}
              placeholder="12345"
              maxLength="5"
              required
              style={{ maxWidth: '200px' }}
            />
          </div>
        </div>

        <button
          type="submit"
          className="btn btn-primary btn-block"
          disabled={loading || !stripe}
        >
          {loading ? 'Processing...' : `Pay $${(formData.grossAmountCents / 100).toFixed(2)}`}
        </button>
      </form>
    </div>
  )
}

function Checkout({ buyerId }) {
  return (
    <div className="page-container">
      <CheckoutForm buyerId={buyerId} />
    </div>
  )
}

export default Checkout

