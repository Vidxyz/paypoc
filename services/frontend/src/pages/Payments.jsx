import { useState, useEffect } from 'react'
import paymentsApi from '../api/paymentsApi'
import '../App.css'

function Payments({ buyerId }) {
  const [payments, setPayments] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filteredPayments, setFilteredPayments] = useState([])

  useEffect(() => {
    loadPayments()
  }, [buyerId])

  const loadPayments = async () => {
    setLoading(true)
    setError('')
    
    try {
      // Fetch payments from API (filtered by buyerId via bearer token)
      const response = await paymentsApi.getPayments({
        page: 0,
        size: 100, // Get up to 100 payments
        sortBy: 'createdAt',
        sortDirection: 'DESC',
      })
      
      if (response.error) {
        throw new Error(response.error)
      }
      
      const paymentList = response.payments || []
      setPayments(paymentList)
      setFilteredPayments(paymentList)
    } catch (err) {
      if (err.response?.status === 401) {
        setError('Unauthorized. Please log in again.')
      } else {
        setError(err.message || 'Failed to load payments')
      }
    } finally {
      setLoading(false)
    }
  }

  const getStateBadge = (state) => {
    const badges = {
      CREATED: 'badge-info',
      CONFIRMING: 'badge-info',
      AUTHORIZED: 'badge-warning',
      CAPTURED: 'badge-success',
      FAILED: 'badge-danger',
    }
    return badges[state] || 'badge-info'
  }

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A'
    try {
      return new Date(dateString).toLocaleString()
    } catch {
      return dateString
    }
  }

  const formatAmount = (cents, currency = 'USD') => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency,
    }).format(cents / 100)
  }

  if (loading) {
    return (
      <div className="page-container">
        <div className="card">
          <div className="spinner"></div>
          <p style={{ textAlign: 'center', color: '#666' }}>Loading payments...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="page-container">
      <div className="card">
        <h2>Payment History</h2>
        <p style={{ color: '#666', marginBottom: '1rem' }}>
          All payments for <strong>{buyerId}</strong>
        </p>

        {error && <div className="alert alert-error">{error}</div>}

        {filteredPayments.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '3rem', color: '#666' }}>
            <p style={{ fontSize: '1.25rem', marginBottom: '1rem' }}>No payments found</p>
            <p>Create a payment from the checkout page to see it here.</p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="table">
              <thead>
                <tr>
                  <th>Payment ID</th>
                  <th>Amount</th>
                  <th>Seller</th>
                  <th>State</th>
                  <th>Created</th>
                  <th>Stripe PI</th>
                </tr>
              </thead>
              <tbody>
                {filteredPayments.map((payment) => (
                  <tr key={payment.id}>
                    <td>
                      <code style={{ fontSize: '0.875rem' }}>
                        {payment.id?.substring(0, 8)}...
                      </code>
                    </td>
                    <td>
                      <strong>{formatAmount(payment.grossAmountCents, payment.currency)}</strong>
                      {payment.platformFeeCents > 0 && (
                        <div style={{ fontSize: '0.75rem', color: '#666' }}>
                          Fee: {formatAmount(payment.platformFeeCents, payment.currency)}
                        </div>
                      )}
                    </td>
                    <td>{payment.sellerId}</td>
                    <td>
                      <span className={`badge ${getStateBadge(payment.state)}`}>
                        {payment.state}
                      </span>
                    </td>
                    <td style={{ fontSize: '0.875rem' }}>
                      {formatDate(payment.createdAt)}
                    </td>
                    <td>
                      {payment.stripePaymentIntentId ? (
                        <code style={{ fontSize: '0.75rem' }}>
                          {payment.stripePaymentIntentId.substring(0, 12)}...
                        </code>
                      ) : (
                        <span style={{ color: '#999' }}>N/A</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div style={{ marginTop: '1rem', textAlign: 'right' }}>
          <button onClick={loadPayments} className="btn btn-secondary">
            Refresh
          </button>
        </div>
      </div>
    </div>
  )
}

export default Payments

