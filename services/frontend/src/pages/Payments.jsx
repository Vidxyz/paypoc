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
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Divider,
  Grid,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import ErrorIcon from '@mui/icons-material/Error'
import WarningIcon from '@mui/icons-material/Warning'
import InfoIcon from '@mui/icons-material/Info'
import PaymentsIcon from '@mui/icons-material/Payments'
import GavelIcon from '@mui/icons-material/Gavel'
import CloseIcon from '@mui/icons-material/Close'
import paymentsApi from '../api/paymentsApi'

function Payments({ buyerId, userEmail }) {
  const [payments, setPayments] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' })
  
  // Chargeback details modal state
  const [chargebackModalOpen, setChargebackModalOpen] = useState(false)
  const [selectedPaymentForChargeback, setSelectedPaymentForChargeback] = useState(null)
  const [chargebackDetails, setChargebackDetails] = useState(null)
  const [loadingChargebackDetails, setLoadingChargebackDetails] = useState(false)

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
      
      // Chargeback info is now included in payment response - no need for N+1 calls!
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


  const handleChargebackClick = async (payment) => {
    if (!payment.hasChargeback || !payment.latestChargebackId) {
      return
    }
    
    setSelectedPaymentForChargeback(payment)
    setLoadingChargebackDetails(true)
    setChargebackModalOpen(true)
    
    try {
      // Fetch full chargeback details
      const chargeback = await paymentsApi.getChargeback(payment.latestChargebackId)
      setChargebackDetails(chargeback)
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Failed to load dispute details: ${err.message || 'Unknown error'}`,
        severity: 'error',
      })
      setChargebackModalOpen(false)
    } finally {
      setLoadingChargebackDetails(false)
    }
  }

  const getChargebackChip = (payment) => {
    if (!payment.hasChargeback) {
      return null
    }
    
    // Buyer perspective: WON (platform won) = buyer lost, LOST (platform lost) = buyer won
    const chips = {
      DISPUTE_CREATED: { label: 'Dispute Filed', color: 'warning' },
      NEEDS_RESPONSE: { label: 'Response Required', color: 'error' },
      UNDER_REVIEW: { label: 'Under Review', color: 'info' },
      WON: { label: 'Dispute Lost', color: 'error' }, // Platform won = buyer lost
      LOST: { label: 'Dispute Won', color: 'success' }, // Platform lost = buyer won
      WITHDRAWN: { label: 'Dispute Withdrawn', color: 'error' }, // Buyer lost (withdrawn)
      WARNING_CLOSED: { label: 'Dispute Lost', color: 'error' }, // Buyer lost
    }
    
    const chip = chips[payment.chargebackState] || { label: payment.chargebackState, color: 'default' }
    
    return (
      <Box>
        <Chip
          label={chip.label}
          color={chip.color}
          size="small"
          sx={{ mb: 0.5, cursor: 'pointer' }}
          onClick={() => handleChargebackClick(payment)}
          icon={<GavelIcon />}
        />
        <Typography variant="caption" color="text.secondary" display="block">
          {formatAmount(payment.chargebackAmountCents, payment.currency)}
        </Typography>
      </Box>
    )
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

  // Buyer-friendly status labels (reversed from platform perspective)
  const getBuyerFriendlyStatusLabel = (state) => {
    const labels = {
      DISPUTE_CREATED: 'Dispute Filed',
      NEEDS_RESPONSE: 'Response Required',
      UNDER_REVIEW: 'Under Review',
      WON: 'Dispute Lost', // Platform won = buyer lost
      LOST: 'Dispute Won', // Platform lost = buyer won
      WITHDRAWN: 'Dispute Withdrawn', // Buyer won
      WARNING_CLOSED: 'Dispute Won', // Platform lost = buyer won
    }
    return labels[state] || state?.replace(/_/g, ' ') || 'Unknown'
  }

  const getBuyerFriendlyStatusColor = (state) => {
    // Buyer perspective: success = buyer won, error = buyer lost
    switch (state) {
      case 'WON': // Platform won = buyer lost
      case 'WITHDRAWN': // Buyer lost (withdrawn)
      case 'WARNING_CLOSED': // Buyer lost
        return 'error'
      case 'LOST': // Platform lost = buyer won
        return 'success'
      case 'NEEDS_RESPONSE':
        return 'error'
      case 'UNDER_REVIEW':
        return 'info'
      case 'DISPUTE_CREATED':
        return 'warning'
      default:
        return 'warning'
    }
  }

  // Buyer-friendly outcome labels (reversed from platform perspective)
  const getBuyerFriendlyOutcomeLabel = (outcome) => {
    const labels = {
      WON: 'Dispute Lost', // Platform won = buyer lost
      LOST: 'Dispute Won', // Platform lost = buyer won
      WITHDRAWN: 'Dispute Withdrawn', // Buyer lost (withdrawn)
      WARNING_CLOSED: 'Dispute Lost', // Buyer lost
    }
    return labels[outcome] || outcome || 'Unknown'
  }

  const getBuyerFriendlyOutcomeColor = (outcome) => {
    // Buyer perspective: success = buyer won, error = buyer lost
    switch (outcome) {
      case 'WON': // Platform won = buyer lost
      case 'WITHDRAWN': // Buyer lost (withdrawn)
      case 'WARNING_CLOSED': // Buyer lost
        return 'error'
      case 'LOST': // Platform lost = buyer won
        return 'success'
      default:
        return 'info'
    }
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
                All payments for <strong>{userEmail || buyerId}</strong>
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
                    <TableCell><strong>Dispute</strong></TableCell>
                    <TableCell><strong>Created</strong></TableCell>
                    <TableCell><strong>Stripe PI</strong></TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {payments.map((payment) => {
                    // Show refund status if payment is REFUNDED or REFUNDING (read-only)
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
                          {getChargebackChip(payment) || (
                            <Typography variant="body2" color="text.disabled">
                              —
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

      {/* Chargeback Details Modal */}
      <Dialog
        open={chargebackModalOpen}
        onClose={() => {
          setChargebackModalOpen(false)
          setSelectedPaymentForChargeback(null)
          setChargebackDetails(null)
        }}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <GavelIcon color="error" />
            <Typography variant="h6">Dispute Details</Typography>
          </Box>
          <Button
            onClick={() => {
              setChargebackModalOpen(false)
              setSelectedPaymentForChargeback(null)
              setChargebackDetails(null)
            }}
            sx={{ minWidth: 'auto', p: 1 }}
          >
            <CloseIcon />
          </Button>
        </DialogTitle>
        <DialogContent>
          {loadingChargebackDetails ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
              <CircularProgress />
            </Box>
          ) : chargebackDetails ? (
            <Box>
              <Grid container spacing={2}>
                <Grid item xs={12}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Dispute Status
                  </Typography>
                  <Chip
                    label={getBuyerFriendlyStatusLabel(chargebackDetails.state)}
                    color={getBuyerFriendlyStatusColor(chargebackDetails.state)}
                    sx={{ mb: 2 }}
                  />
                </Grid>
                
                <Grid item xs={12}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Amount in Dispute
                  </Typography>
                  <Typography variant="h6" gutterBottom>
                    {formatAmount(chargebackDetails.chargebackAmountCents, chargebackDetails.currency)}
                  </Typography>
                </Grid>
                
                {chargebackDetails.reason && (
                  <Grid item xs={12}>
                    <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                      Dispute Reason
                    </Typography>
                    <Typography variant="body1" gutterBottom>
                      {chargebackDetails.reason.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase())}
                    </Typography>
                  </Grid>
                )}
                
                {chargebackDetails.evidenceDueBy && (
                  <Grid item xs={12}>
                    <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                      Evidence Due By
                    </Typography>
                    <Typography variant="body1" color={new Date(chargebackDetails.evidenceDueBy) < new Date() ? 'error' : 'text.primary'}>
                      {formatDate(chargebackDetails.evidenceDueBy)}
                    </Typography>
                  </Grid>
                )}
                
                {chargebackDetails.outcome && (
                  <Grid item xs={12}>
                    <Divider sx={{ my: 2 }} />
                    <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                      Final Outcome
                    </Typography>
                    <Chip
                      label={getBuyerFriendlyOutcomeLabel(chargebackDetails.outcome)}
                      color={getBuyerFriendlyOutcomeColor(chargebackDetails.outcome)}
                      sx={{ mb: 1 }}
                    />
                    {chargebackDetails.closedAt && (
                      <Typography variant="caption" color="text.secondary" display="block">
                        Resolved: {formatDate(chargebackDetails.closedAt)}
                      </Typography>
                    )}
                  </Grid>
                )}
                
                <Grid item xs={12}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Created
                  </Typography>
                  <Typography variant="body2">
                    {formatDate(chargebackDetails.createdAt)}
                  </Typography>
                </Grid>
              </Grid>
            </Box>
          ) : (
            <Alert severity="error">
              Failed to load dispute details
            </Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button
            onClick={() => {
              setChargebackModalOpen(false)
              setSelectedPaymentForChargeback(null)
              setChargebackDetails(null)
            }}
            variant="contained"
          >
            Close
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  )
}

export default Payments
