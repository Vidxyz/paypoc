import { useState, useEffect } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Button,
  Box,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,
  CircularProgress,
  Alert,
  Snackbar,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import ErrorIcon from '@mui/icons-material/Error'
import WarningIcon from '@mui/icons-material/Warning'
import InfoIcon from '@mui/icons-material/Info'
import PaymentsIcon from '@mui/icons-material/Payments'
import ConfirmationModal from '../components/ConfirmationModal'
import paymentsApi from '../api/paymentsApi'

function Payments({ buyerId }) {
  const [payments, setPayments] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refundingPaymentId, setRefundingPaymentId] = useState(null)
  const [refunds, setRefunds] = useState({}) // Map of paymentId -> refunds array
  const [confirmModalOpen, setConfirmModalOpen] = useState(false)
  const [selectedPaymentForRefund, setSelectedPaymentForRefund] = useState(null)
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' })

  useEffect(() => {
    loadPayments()
  }, [buyerId])

  const loadPayments = async () => {
    setLoading(true)
    setError('')
    
    try {
      const response = await paymentsApi.getPayments({
        page: 0,
        size: 100,
        sortBy: 'createdAt',
        sortDirection: 'DESC',
      })
      
      if (response.error) {
        throw new Error(response.error)
      }
      
      const paymentList = response.payments || []
      setPayments(paymentList)
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


  const handleRefundClick = async (payment) => {
    // Sanity check: verify payment is in CAPTURED state (not already refunding or refunded)
    if (payment.state !== 'CAPTURED') {
      if (payment.state === 'REFUNDING') {
        setSnackbar({
          open: true,
          message: 'This payment is already being refunded.',
          severity: 'warning',
        })
      } else if (payment.state === 'REFUNDED') {
        setSnackbar({
          open: true,
          message: 'This payment has already been refunded.',
          severity: 'error',
        })
      } else {
        setSnackbar({
          open: true,
          message: `This payment cannot be refunded. Current state: ${payment.state}`,
          severity: 'error',
        })
      }
      return
    }

    setSelectedPaymentForRefund(payment)
    setConfirmModalOpen(true)
  }

  const handleRefundConfirm = async () => {
    const payment = selectedPaymentForRefund
    if (!payment) return

    setConfirmModalOpen(false)
    setRefundingPaymentId(payment.id)
    setError('')

    try {
      // Double-check that payment is still in CAPTURED state (sanity check)
      // Reload payment to get latest state
      const latestPayment = await paymentsApi.getPayment(payment.id)
      if (latestPayment.state !== 'CAPTURED') {
        if (latestPayment.state === 'REFUNDING') {
          throw new Error('This payment is already being refunded.')
        } else if (latestPayment.state === 'REFUNDED') {
          throw new Error('This payment has already been refunded.')
        } else {
          throw new Error(`This payment cannot be refunded. Current state: ${latestPayment.state}`)
        }
      }

      const refundResponse = await paymentsApi.createRefund(payment.id)
      
      if (refundResponse.error) {
        throw new Error(refundResponse.error)
      }

      // Refresh payments to get updated state
      await loadPayments()
      
      // Load refunds for this payment to update UI
      try {
        const refundResponse2 = await paymentsApi.getRefundsForPayment(payment.id)
        if (refundResponse2.refunds) {
          setRefunds(prev => ({
            ...prev,
            [payment.id]: refundResponse2.refunds
          }))
        }
      } catch (err) {
        console.error(`Failed to load refunds after creation: ${err.message}`)
      }

      setSnackbar({
        open: true,
        message: 'Refund initiated successfully! The refund is being processed.',
        severity: 'success',
      })
    } catch (err) {
      let errorMessage = 'Failed to create refund'
      
      if (err.response?.status === 400) {
        errorMessage = err.response?.data?.error || 'This payment cannot be refunded. It may have already been refunded.'
      } else if (err.message) {
        errorMessage = err.message
      } else if (err.response?.data?.error) {
        errorMessage = err.response.data.error
      }

      setSnackbar({
        open: true,
        message: errorMessage,
        severity: 'error',
      })
    } finally {
      setRefundingPaymentId(null)
      setSelectedPaymentForRefund(null)
    }
  }

  const getStateChip = (state) => {
    const chips = {
      CREATED: { label: state, color: 'info', icon: <InfoIcon /> },
      CONFIRMING: { label: state, color: 'info', icon: <InfoIcon /> },
      AUTHORIZED: { label: state, color: 'warning', icon: <WarningIcon /> },
      CAPTURED: { label: state, color: 'success', icon: <CheckCircleIcon /> },
      REFUNDING: { label: state, color: 'warning', icon: <WarningIcon /> },
      REFUNDED: { label: state, color: 'secondary', icon: <CheckCircleIcon /> },
      FAILED: { label: state, color: 'error', icon: <ErrorIcon /> },
    }
    const chip = chips[state] || { label: state, color: 'default', icon: <InfoIcon /> }
    return (
      <Chip
        icon={chip.icon}
        label={chip.label}
        color={chip.color}
        size="small"
      />
    )
  }

  const hasRefund = (paymentId) => {
    return refunds[paymentId] && refunds[paymentId].length > 0
  }

  const getRefundStatus = (paymentId) => {
    const paymentRefunds = refunds[paymentId]
    if (!paymentRefunds || paymentRefunds.length === 0) {
      return null
    }
    const latestRefund = paymentRefunds[0]
    return latestRefund.state
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
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Card>
          <CardContent sx={{ textAlign: 'center', py: 8 }}>
            <CircularProgress />
            <Typography variant="body1" color="text.secondary" sx={{ mt: 2 }}>
              Loading payments...
            </Typography>
          </CardContent>
        </Card>
      </Container>
    )
  }

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
            <Box>
              <Typography variant="h4" component="h2" gutterBottom>
                Payment History
              </Typography>
              <Typography variant="body2" color="text.secondary">
                All payments for <strong>{buyerId}</strong>
              </Typography>
            </Box>
            <Button
              onClick={loadPayments}
              variant="outlined"
              startIcon={<RefreshIcon />}
            >
              Refresh
            </Button>
          </Box>

          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}

          {payments.length === 0 ? (
            <Box sx={{ textAlign: 'center', py: 8 }}>
              <PaymentsIcon sx={{ fontSize: 64, color: 'text.secondary', mb: 2 }} />
              <Typography variant="h6" color="text.secondary" gutterBottom>
                No payments found
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Create a payment from the checkout page to see it here.
              </Typography>
            </Box>
          ) : (
            <TableContainer component={Paper} variant="outlined">
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell><strong>Payment ID</strong></TableCell>
                    <TableCell><strong>Amount</strong></TableCell>
                    <TableCell><strong>Seller</strong></TableCell>
                    <TableCell><strong>State</strong></TableCell>
                    <TableCell><strong>Refund</strong></TableCell>
                    <TableCell><strong>Created</strong></TableCell>
                    <TableCell><strong>Stripe PI</strong></TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {payments.map((payment) => {
                    const paymentRefunds = refunds[payment.id] || []
                    const refundStatus = getRefundStatus(payment.id)
                    // Payment is refundable if it's CAPTURED (not REFUNDING or REFUNDED)
                    const isRefundable = payment.state === 'CAPTURED'
                    // Use payment state as primary indicator for refund status
                    const isRefunded = payment.state === 'REFUNDED'
                    const isRefunding = payment.state === 'REFUNDING'
                    
                    return (
                      <TableRow key={payment.id} hover>
                        <TableCell>
                          <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                            {payment.id?.substring(0, 8)}...
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="body1" fontWeight="bold">
                            {formatAmount(payment.grossAmountCents, payment.currency)}
                          </Typography>
                          {payment.platformFeeCents > 0 && (
                            <Typography variant="caption" color="text.secondary">
                              Fee: {formatAmount(payment.platformFeeCents, payment.currency)}
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell>{payment.sellerId}</TableCell>
                        <TableCell>
                          {getStateChip(payment.state)}
                        </TableCell>
                        <TableCell>
                          {isRefundable ? (
                            <Button
                              onClick={() => handleRefundClick(payment)}
                              disabled={refundingPaymentId === payment.id}
                              variant="outlined"
                              color="warning"
                              size="small"
                            >
                              {refundingPaymentId === payment.id ? 'Processing...' : 'Refund'}
                            </Button>
                          ) : isRefunding ? (
                            <Chip
                              label="Refunding..."
                              color="warning"
                              size="small"
                            />
                          ) : isRefunded ? (
                            <Chip
                              label="Refunded"
                              color="secondary"
                              size="small"
                            />
                          ) : hasRefund(payment.id) && refundStatus === 'FAILED' ? (
                            <Chip
                              label="Refund Failed"
                              color="error"
                              size="small"
                            />
                          ) : (
                            <Typography variant="body2" color="text.disabled">
                              —
                            </Typography>
                          )}
                          {paymentRefunds.length > 0 && (
                            <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
                              {formatAmount(paymentRefunds[0].refundAmountCents, paymentRefunds[0].currency)}
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2">
                            {formatDate(payment.createdAt)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          {payment.stripePaymentIntentId ? (
                            <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                              {payment.stripePaymentIntentId.substring(0, 12)}...
                            </Typography>
                          ) : (
                            <Typography variant="body2" color="text.disabled">
                              N/A
                            </Typography>
                          )}
                        </TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      <ConfirmationModal
        open={confirmModalOpen}
        onClose={() => {
          setConfirmModalOpen(false)
          setSelectedPaymentForRefund(null)
        }}
        onConfirm={handleRefundConfirm}
        title="Confirm Refund"
        message={
          selectedPaymentForRefund
            ? `Are you sure you want to refund ${formatAmount(selectedPaymentForRefund.grossAmountCents, selectedPaymentForRefund.currency)}? This action cannot be undone.`
            : 'Are you sure you want to refund this payment? This action cannot be undone.'
        }
        confirmText="Refund"
        cancelText="Cancel"
        severity="warning"
      />

      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={() => setSnackbar({ ...snackbar, open: false })}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert
          onClose={() => setSnackbar({ ...snackbar, open: false })}
          severity={snackbar.severity}
          sx={{ width: '100%' }}
        >
          {snackbar.message}
        </Alert>
      </Snackbar>
    </Container>
  )
}

export default Payments
