import { useState, useEffect } from 'react'
import { loadStripe } from '@stripe/stripe-js'
import { Elements, CardElement, useStripe, useElements } from '@stripe/react-stripe-js'
import paymentsApi from '../api/paymentsApi'
import '../App.css'
import './Checkout.css'

// Initialize Stripe - using test publishable key
// In production, this should come from environment variables
const stripePromise = loadStripe(import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY || 'missing_stripe_publishable_key')

function CheckoutForm({ buyerId, onPaymentSuccess }) {
  const stripe = useStripe()
  const elements = useElements()
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
  
  const [selectedTestCard, setSelectedTestCard] = useState(null)

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

  const copyToClipboard = (text, buttonElement) => {
    navigator.clipboard.writeText(text).then(() => {
      // Show a brief success message
      if (buttonElement) {
        const originalText = buttonElement.textContent
        buttonElement.textContent = 'Copied!'
        buttonElement.style.background = '#28a745'
        setTimeout(() => {
          buttonElement.textContent = originalText
          buttonElement.style.background = ''
        }, 1000)
      }
    }).catch(() => {
      // Fallback for older browsers
      const textArea = document.createElement('textarea')
      textArea.value = text
      document.body.appendChild(textArea)
      textArea.select()
      document.execCommand('copy')
      document.body.removeChild(textArea)
      if (buttonElement) {
        const originalText = buttonElement.textContent
        buttonElement.textContent = 'Copied!'
        setTimeout(() => {
          buttonElement.textContent = originalText
        }, 1000)
      }
    })
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

    if (!stripe || !elements) {
      setError('Stripe not loaded. Please wait...')
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

      // Step 2: Get card element from Stripe Elements
      const cardElement = elements.getElement(CardElement)
      if (!cardElement) {
        throw new Error('Card element not found')
      }

      // Step 3: Confirm payment with Stripe Elements
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
            ${(formData.grossAmountCents / 100).toFixed(2)} {formData.currency}
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
            <option value="CAD">CAD</option>
            <option value="USD">USD</option>
            <option value="EUR">EUR</option>
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
                  onClick={() => setSelectedTestCard(preset)}
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
              <div className="alert alert-info" style={{ marginBottom: '1rem', padding: '1rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
                  <div>
                    <strong>{selectedTestCard.name}</strong>
                    <div style={{ fontSize: '0.875rem', color: '#666', marginTop: '0.25rem' }}>
                      {selectedTestCard.description}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setSelectedTestCard(null)}
                    style={{ 
                      background: 'none', 
                      border: 'none', 
                      fontSize: '1.25rem', 
                      cursor: 'pointer',
                      color: '#666',
                      padding: '0',
                      lineHeight: '1'
                    }}
                  >
                    ×
                  </button>
                </div>
                
                <div style={{ 
                  background: '#f8f9fa', 
                  padding: '1rem', 
                  borderRadius: '8px',
                  border: '1px solid #dee2e6'
                }}>
                  <div style={{ 
                    fontSize: '0.875rem', 
                    color: '#666', 
                    marginBottom: '0.75rem',
                    fontWeight: '500'
                  }}>
                    Copy and paste these details into the Stripe card element below:
                  </div>
                  
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '0.75rem' }}>
                    <div>
                      <div style={{ fontSize: '0.75rem', color: '#666', marginBottom: '0.25rem' }}>Card Number</div>
                      <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                        <code style={{ 
                          flex: 1, 
                          padding: '0.5rem', 
                          background: 'white', 
                          borderRadius: '4px',
                          border: '1px solid #dee2e6',
                          fontSize: '0.875rem',
                          fontFamily: 'monospace'
                        }}>
                          {selectedTestCard.cardNumber}
                        </code>
                        <button
                          type="button"
                          onClick={(e) => copyToClipboard(selectedTestCard.cardNumber.replace(/\s/g, ''), e.target)}
                          className="btn btn-secondary"
                          style={{ 
                            padding: '0.5rem 0.75rem', 
                            fontSize: '0.75rem',
                            whiteSpace: 'nowrap',
                            minWidth: '60px'
                          }}
                        >
                          Copy
                        </button>
                      </div>
                    </div>
                    
                    <div>
                      <div style={{ fontSize: '0.75rem', color: '#666', marginBottom: '0.25rem' }}>Expiry</div>
                      <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                        <code style={{ 
                          flex: 1, 
                          padding: '0.5rem', 
                          background: 'white', 
                          borderRadius: '4px',
                          border: '1px solid #dee2e6',
                          fontSize: '0.875rem',
                          fontFamily: 'monospace'
                        }}>
                          {selectedTestCard.expiry}
                        </code>
                        <button
                          type="button"
                          onClick={(e) => copyToClipboard(selectedTestCard.expiry, e.target)}
                          className="btn btn-secondary"
                          style={{ 
                            padding: '0.5rem 0.75rem', 
                            fontSize: '0.75rem',
                            whiteSpace: 'nowrap',
                            minWidth: '60px'
                          }}
                        >
                          Copy
                        </button>
                      </div>
                    </div>
                    
                    <div>
                      <div style={{ fontSize: '0.75rem', color: '#666', marginBottom: '0.25rem' }}>CVC</div>
                      <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                        <code style={{ 
                          flex: 1, 
                          padding: '0.5rem', 
                          background: 'white', 
                          borderRadius: '4px',
                          border: '1px solid #dee2e6',
                          fontSize: '0.875rem',
                          fontFamily: 'monospace'
                        }}>
                          {selectedTestCard.cvc}
                        </code>
                        <button
                          type="button"
                          onClick={(e) => copyToClipboard(selectedTestCard.cvc, e.target)}
                          className="btn btn-secondary"
                          style={{ 
                            padding: '0.5rem 0.75rem', 
                            fontSize: '0.75rem',
                            whiteSpace: 'nowrap',
                            minWidth: '60px'
                          }}
                        >
                          Copy
                        </button>
                      </div>
                    </div>
                    
                    <div>
                      <div style={{ fontSize: '0.75rem', color: '#666', marginBottom: '0.25rem' }}>ZIP Code</div>
                      <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                        <code style={{ 
                          flex: 1, 
                          padding: '0.5rem', 
                          background: 'white', 
                          borderRadius: '4px',
                          border: '1px solid #dee2e6',
                          fontSize: '0.875rem',
                          fontFamily: 'monospace'
                        }}>
                          {selectedTestCard.zip}
                        </code>
                        <button
                          type="button"
                          onClick={(e) => copyToClipboard(selectedTestCard.zip, e.target)}
                          className="btn btn-secondary"
                          style={{ 
                            padding: '0.5rem 0.75rem', 
                            fontSize: '0.75rem',
                            whiteSpace: 'nowrap',
                            minWidth: '60px'
                          }}
                        >
                          Copy
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>
          
          {/* Stripe Card Element */}
          <div className="stripe-card-element">
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
          </div>
        </div>

        <button
          type="submit"
          className="btn btn-primary btn-block"
          disabled={loading || !stripe || !elements}
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
      <Elements stripe={stripePromise}>
        <CheckoutForm buyerId={buyerId} />
      </Elements>
    </div>
  )
}

export default Checkout
