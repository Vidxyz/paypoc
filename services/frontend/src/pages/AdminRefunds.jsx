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
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import SearchIcon from '@mui/icons-material/Search'
import { getAdminPayments, createRefund } from '../api/adminApi'
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

  const handleRefundClick = (payment) => {
    if (payment.state !== 'CAPTURED') {
      setSnackbar({
        open: true,
        message: 'Only CAPTURED payments can be refunded',
        severity: 'warning',
      })
      return
    }
    if (payment.refundedAt) {
      setSnackbar({
        open: true,
        message: 'This payment has already been refunded',
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
      // Reload payments after a short delay
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
                      <TableCell>Created</TableCell>
                      <TableCell>Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {filteredPayments.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={8} align="center">
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
                            {new Date(payment.createdAt).toLocaleString()}
                          </TableCell>
                          <TableCell>
                            <Button
                              variant="outlined"
                              size="small"
                              color="error"
                              onClick={() => handleRefundClick(payment)}
                              disabled={
                                payment.state !== 'CAPTURED' ||
                                !!payment.refundedAt ||
                                refundingPaymentId === payment.id
                              }
                            >
                              {refundingPaymentId === payment.id ? (
                                <CircularProgress size={16} />
                              ) : (
                                'Refund'
                              )}
                            </Button>
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

