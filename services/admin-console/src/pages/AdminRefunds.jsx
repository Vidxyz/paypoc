import { useState, useEffect } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Button,
  Chip,
  CircularProgress,
  Alert,
  Snackbar,
  Box,
  Pagination,
  TextField,
  InputAdornment,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Grid,
  Divider,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import SearchIcon from '@mui/icons-material/Search'
import GavelIcon from '@mui/icons-material/Gavel'
import CloseIcon from '@mui/icons-material/Close'
import { getAdminPayments, createRefund, getChargeback } from '../api/adminApi'
import ConfirmationModal from '../components/ConfirmationModal'

function AdminRefunds() {
  const [payments, setPayments] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refundingPaymentId, setRefundingPaymentId] = useState(null)
  const [confirmModalOpen, setConfirmModalOpen] = useState(false)
  const [selectedPayment, setSelectedPayment] = useState(null)
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' })
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [searchTerm, setSearchTerm] = useState('')
  
  // Chargeback details modal state
  const [chargebackModalOpen, setChargebackModalOpen] = useState(false)
  const [selectedPaymentForChargeback, setSelectedPaymentForChargeback] = useState(null)
  const [chargebackDetails, setChargebackDetails] = useState(null)
  const [loadingChargebackDetails, setLoadingChargebackDetails] = useState(false)

  const loadPayments = async () => {
    setLoading(true)
    setError('')
    try {
      const response = await getAdminPayments(page, 50, 'createdAt', 'DESC')
      if (response.error) {
        setError(response.error)
      } else {
        setPayments(response.payments || [])
        setTotalPages(Math.ceil((response.total || 0) / 50))
      }
    } catch (err) {
      setError(`Failed to load payments: ${err.message}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadPayments()
  }, [page])

  // Check if a payment can be refunded
  const canRefundPayment = (payment) => {
    // Must be CAPTURED
    if (payment.state !== 'CAPTURED') {
      return { canRefund: false, reason: 'Only CAPTURED payments can be refunded' }
    }
    
    // Must not already be refunded
    if (payment.refundedAt) {
      return { canRefund: false, reason: 'This payment has already been refunded' }
    }
    
    // Check chargeback status
    if (payment.hasChargeback) {
      const chargebackState = payment.chargebackState
      // Only allow refund if chargeback was WON or WARNING_CLOSED (platform won)
      if (chargebackState !== 'WON' && chargebackState !== 'WARNING_CLOSED') {
        return { 
          canRefund: false, 
          reason: `Cannot refund payment with active chargeback (status: ${chargebackState}). Only payments with WON or WARNING_CLOSED chargebacks can be refunded.` 
        }
      }
    }
    
    return { canRefund: true, reason: null }
  }

  const handleRefundClick = (payment) => {
    const { canRefund, reason } = canRefundPayment(payment)
    
    if (!canRefund) {
      setSnackbar({
        open: true,
        message: reason,
        severity: 'warning',
      })
      return
    }
    
    setSelectedPayment(payment)
    setConfirmModalOpen(true)
  }

  const handleRefundConfirm = async () => {
    if (!selectedPayment) return

    setRefundingPaymentId(selectedPayment.id)
    setConfirmModalOpen(false)

    try {
      await createRefund(selectedPayment.id)
      setSnackbar({
        open: true,
        message: 'Refund initiated successfully',
        severity: 'success',
      })
      setTimeout(() => {
        loadPayments()
      }, 1000)
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Refund failed: ${err.response?.data?.error || err.message}`,
        severity: 'error',
      })
    } finally {
      setRefundingPaymentId(null)
      setSelectedPayment(null)
    }
  }

  const filteredPayments = payments.filter((payment) => {
    if (!searchTerm) return true
    const search = searchTerm.toLowerCase()
    return (
      payment.id.toLowerCase().includes(search) ||
      payment.buyerId?.toLowerCase().includes(search) ||
      payment.sellerId?.toLowerCase().includes(search) ||
      payment.description?.toLowerCase().includes(search)
    )
  })

  const getStateColor = (state) => {
    switch (state) {
      case 'CAPTURED':
        return 'success'
      case 'REFUNDED':
        return 'info'
      case 'AUTHORIZED':
        return 'warning'
      case 'FAILED':
        return 'error'
      default:
        return 'default'
    }
  }

  const handleChargebackClick = async (payment) => {
    if (!payment.hasChargeback || !payment.latestChargebackId) {
      return
    }
    
    setSelectedPaymentForChargeback(payment)
    setLoadingChargebackDetails(true)
    setChargebackModalOpen(true)
    
    try {
      const chargeback = await getChargeback(payment.latestChargebackId)
      setChargebackDetails(chargeback)
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Failed to load chargeback details: ${err.message || 'Unknown error'}`,
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
    
    const chips = {
      DISPUTE_CREATED: { label: 'Dispute Created', color: 'warning' },
      NEEDS_RESPONSE: { label: 'Needs Response', color: 'error' },
      UNDER_REVIEW: { label: 'Under Review', color: 'info' },
      WON: { label: 'Won', color: 'success' },
      LOST: { label: 'Lost', color: 'error' },
      WITHDRAWN: { label: 'Withdrawn', color: 'info' },
      WARNING_CLOSED: { label: 'Warning Closed', color: 'warning' },
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

  const formatAmount = (cents, currency = 'USD') => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency,
    }).format(cents / 100)
  }

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A'
    try {
      return new Date(dateString).toLocaleString()
    } catch {
      return dateString
    }
  }

  return (
    <Container maxWidth="xl" sx={{ mt: 4, mb: 4 }}>
      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
            <Typography variant="h4" component="h1">
              Refund Transactions
            </Typography>
            <Button
              variant="outlined"
              startIcon={<RefreshIcon />}
              onClick={loadPayments}
              disabled={loading}
            >
              Refresh
            </Button>
          </Box>

          <Box sx={{ mb: 3 }}>
            <TextField
              fullWidth
              placeholder="Search by payment ID, buyer, seller, or description..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              InputProps={{
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon />
                  </InputAdornment>
                ),
              }}
            />
          </Box>

          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}

          {loading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
              <CircularProgress />
            </Box>
          ) : (
            <>
              <TableContainer component={Paper}>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Payment ID</TableCell>
                      <TableCell>Buyer</TableCell>
                      <TableCell>Seller</TableCell>
                      <TableCell>Amount</TableCell>
                      <TableCell>Currency</TableCell>
                      <TableCell>State</TableCell>
                      <TableCell>Chargeback</TableCell>
                      <TableCell>Created</TableCell>
                      <TableCell>Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {filteredPayments.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={9} align="center">
                          <Typography variant="body2" color="text.secondary">
                            No payments found
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ) : (
                      filteredPayments.map((payment) => (
                        <TableRow key={payment.id}>
                          <TableCell>
                            <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                              {payment.id.substring(0, 8)}...
                            </Typography>
                          </TableCell>
                          <TableCell>{payment.buyerId}</TableCell>
                          <TableCell>{payment.sellerId}</TableCell>
                          <TableCell>
                            ${(payment.grossAmountCents / 100).toFixed(2)}
                          </TableCell>
                          <TableCell>{payment.currency}</TableCell>
                          <TableCell>
                            <Chip
                              label={payment.state}
                              color={getStateColor(payment.state)}
                              size="small"
                            />
                          </TableCell>
                          <TableCell>
                            {getChargebackChip(payment) || (
                              <Typography variant="caption" color="text.secondary">
                                None
                              </Typography>
                            )}
                          </TableCell>
                          <TableCell>
                            {new Date(payment.createdAt).toLocaleString()}
                          </TableCell>
                          <TableCell>
                            {(() => {
                              const { canRefund } = canRefundPayment(payment)
                              return (
                                <Button
                                  variant="outlined"
                                  size="small"
                                  color="error"
                                  onClick={() => handleRefundClick(payment)}
                                  disabled={
                                    !canRefund ||
                                    refundingPaymentId === payment.id
                                  }
                                >
                                  {refundingPaymentId === payment.id ? (
                                    <CircularProgress size={16} />
                                  ) : (
                                    'Refund'
                                  )}
                                </Button>
                              )
                            })()}
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </TableContainer>

              {totalPages > 1 && (
                <Box sx={{ display: 'flex', justifyContent: 'center', mt: 3 }}>
                  <Pagination
                    count={totalPages}
                    page={page + 1}
                    onChange={(e, value) => setPage(value - 1)}
                    color="primary"
                  />
                </Box>
              )}
            </>
          )}
        </CardContent>
      </Card>

      <ConfirmationModal
        open={confirmModalOpen}
        onClose={() => {
          setConfirmModalOpen(false)
          setSelectedPayment(null)
        }}
        onConfirm={handleRefundConfirm}
        title="Confirm Refund"
        message={
          selectedPayment
            ? `Are you sure you want to refund payment ${selectedPayment.id.substring(0, 8)}... for $${(selectedPayment.grossAmountCents / 100).toFixed(2)} ${selectedPayment.currency}?`
            : ''
        }
      />

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
            <Typography variant="h6">Chargeback Details</Typography>
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
                    Chargeback Status
                  </Typography>
                  <Chip
                    label={chargebackDetails.state?.replace(/_/g, ' ') || 'Unknown'}
                    color={
                      chargebackDetails.state === 'WON' ? 'success' :
                      chargebackDetails.state === 'LOST' ? 'error' :
                      chargebackDetails.state === 'WARNING_CLOSED' ? 'warning' :
                      chargebackDetails.state === 'NEEDS_RESPONSE' ? 'error' :
                      chargebackDetails.state === 'UNDER_REVIEW' ? 'info' :
                      'warning'
                    }
                    sx={{ mb: 2 }}
                  />
                </Grid>
                
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Chargeback Amount
                  </Typography>
                  <Typography variant="h6" gutterBottom>
                    {formatAmount(chargebackDetails.chargebackAmountCents, chargebackDetails.currency)}
                  </Typography>
                </Grid>
                
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Dispute Fee
                  </Typography>
                  <Typography variant="body1" gutterBottom>
                    {formatAmount(chargebackDetails.disputeFeeCents, chargebackDetails.currency)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    (Not refunded even if won)
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
                      label={chargebackDetails.outcome}
                      color={
                        chargebackDetails.outcome === 'WON' ? 'success' :
                        chargebackDetails.outcome === 'LOST' ? 'error' :
                        chargebackDetails.outcome === 'WARNING_CLOSED' ? 'warning' :
                        'info'
                      }
                      sx={{ mb: 1 }}
                    />
                    {chargebackDetails.closedAt && (
                      <Typography variant="caption" color="text.secondary" display="block">
                        Closed: {formatDate(chargebackDetails.closedAt)}
                      </Typography>
                    )}
                  </Grid>
                )}
                
                <Grid item xs={12}>
                  <Divider sx={{ my: 2 }} />
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Stripe Dispute ID
                  </Typography>
                  <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                    {chargebackDetails.stripeDisputeId}
                  </Typography>
                </Grid>
                
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
              Failed to load chargeback details
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

      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={() => setSnackbar({ ...snackbar, open: false })}
        message={snackbar.message}
      />
    </Container>
  )
}

export default AdminRefunds

