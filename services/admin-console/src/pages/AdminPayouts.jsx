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
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import PaymentIcon from '@mui/icons-material/Payment'
import { getAdminSellers, createPayoutForSeller, getPayouts } from '../api/adminApi'
import ConfirmationModal from '../components/ConfirmationModal'

function AdminPayouts() {
  const [sellers, setSellers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' })
  const [payoutingSellerId, setPayoutingSellerId] = useState(null)
  const [confirmModalOpen, setConfirmModalOpen] = useState(false)
  const [selectedSeller, setSelectedSeller] = useState(null)
  const [payoutsDialogOpen, setPayoutsDialogOpen] = useState(false)
  const [selectedSellerPayouts, setSelectedSellerPayouts] = useState([])
  const [payoutsLoading, setPayoutsLoading] = useState(false)

  const loadSellers = async () => {
    setLoading(true)
    setError('')
    try {
      const response = await getAdminSellers()
      if (response.error) {
        setError(response.error)
      } else {
        setSellers(response.sellers || [])
      }
    } catch (err) {
      setError(`Failed to load sellers: ${err.message}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadSellers()
  }, [])

  const handlePayoutClick = (seller) => {
    if (seller.balanceCents === 0) {
      setSnackbar({
        open: true,
        message: 'This seller has no pending funds',
        severity: 'warning',
      })
      return
    }
    setSelectedSeller(seller)
    setConfirmModalOpen(true)
  }

  const handlePayoutConfirm = async () => {
    if (!selectedSeller) return

    setPayoutingSellerId(selectedSeller.sellerId)
    setConfirmModalOpen(false)

    try {
      await createPayoutForSeller(selectedSeller.sellerId, selectedSeller.currency)
      setSnackbar({
        open: true,
        message: `Payout initiated successfully for ${selectedSeller.sellerId}`,
        severity: 'success',
      })
      setTimeout(() => {
        loadSellers()
      }, 1000)
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Payout failed: ${err.response?.data?.error || err.message}`,
        severity: 'error',
      })
    } finally {
      setPayoutingSellerId(null)
      setSelectedSeller(null)
    }
  }

  const handleViewPayouts = async (seller) => {
    setPayoutsDialogOpen(true)
    setSelectedSellerPayouts([])
    setPayoutsLoading(true)
    try {
      const response = await getPayouts(seller.sellerId)
      if (response.payouts) {
        setSelectedSellerPayouts(response.payouts)
      }
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Failed to load payouts: ${err.message}`,
        severity: 'error',
      })
    } finally {
      setPayoutsLoading(false)
    }
  }

  const sellersWithBalance = sellers.filter((s) => s.balanceCents !== 0)
  const sellersWithoutBalance = sellers.filter((s) => s.balanceCents === 0)

  return (
    <Container maxWidth="xl" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
            <Typography 
              variant="h4" 
              component="h1"
              sx={{ 
                fontWeight: 700,
                background: 'linear-gradient(135deg, #d32f2f 0%, #b71c1c 100%)',
                backgroundClip: 'text',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
              }}
            >
              Seller Payouts
            </Typography>
            <Button
              variant="outlined"
              startIcon={<RefreshIcon />}
              onClick={loadSellers}
              disabled={loading}
            >
              Refresh
            </Button>
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
              <Typography variant="h6" gutterBottom sx={{ mt: 3, mb: 2 }}>
                Sellers with Pending Funds ({sellersWithBalance.length})
              </Typography>
              <TableContainer component={Paper} sx={{ mb: 4 }}>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Seller ID</TableCell>
                      <TableCell>Currency</TableCell>
                      <TableCell>Balance</TableCell>
                      <TableCell>Account ID</TableCell>
                      <TableCell>Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {sellersWithBalance.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={5} align="center">
                          <Typography variant="body2" color="text.secondary">
                            No sellers with pending funds
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ) : (
                      sellersWithBalance.map((seller) => (
                        <TableRow key={`${seller.sellerId}-${seller.currency}`}>
                          <TableCell>
                            <Typography variant="body1" fontWeight="medium">
                              {seller.sellerId}
                            </Typography>
                          </TableCell>
                          <TableCell>{seller.currency}</TableCell>
                          <TableCell>
                            <Typography variant="body1" fontWeight="bold" color="success.main">
                              ${(seller.balanceCents / 100).toFixed(2)}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '0.75rem' }}>
                              {seller.accountId.substring(0, 8)}...
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Box sx={{ display: 'flex', gap: 1 }}>
                              <Button
                                variant="contained"
                                size="small"
                                startIcon={
                                  payoutingSellerId === seller.sellerId ? (
                                    <CircularProgress size={16} />
                                  ) : (
                                    <PaymentIcon />
                                  )
                                }
                                onClick={() => handlePayoutClick(seller)}
                                disabled={payoutingSellerId === seller.sellerId}
                              >
                                Payout
                              </Button>
                              <Button
                                variant="outlined"
                                size="small"
                                onClick={() => handleViewPayouts(seller)}
                              >
                                View Payouts
                              </Button>
                            </Box>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </TableContainer>

              {sellersWithoutBalance.length > 0 && (
                <>
                  <Typography variant="h6" gutterBottom sx={{ mt: 3, mb: 2 }}>
                    Sellers with Zero Balance ({sellersWithoutBalance.length})
                  </Typography>
                  <TableContainer component={Paper}>
                    <Table>
                      <TableHead>
                        <TableRow>
                          <TableCell>Seller ID</TableCell>
                          <TableCell>Currency</TableCell>
                          <TableCell>Balance</TableCell>
                          <TableCell>Account ID</TableCell>
                          <TableCell>Actions</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {sellersWithoutBalance.map((seller) => (
                          <TableRow key={`${seller.sellerId}-${seller.currency}`}>
                            <TableCell>{seller.sellerId}</TableCell>
                            <TableCell>{seller.currency}</TableCell>
                            <TableCell>
                              <Chip label="$0.00" size="small" />
                            </TableCell>
                            <TableCell>
                              <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '0.75rem' }}>
                                {seller.accountId.substring(0, 8)}...
                              </Typography>
                            </TableCell>
                            <TableCell>
                              <Button
                                variant="outlined"
                                size="small"
                                onClick={() => handleViewPayouts(seller)}
                              >
                                View Payouts
                              </Button>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </>
              )}
            </>
          )}
        </CardContent>
      </Card>

      <ConfirmationModal
        open={confirmModalOpen}
        onClose={() => {
          setConfirmModalOpen(false)
          setSelectedSeller(null)
        }}
        onConfirm={handlePayoutConfirm}
        title="Confirm Payout"
        message={
          selectedSeller
            ? `Are you sure you want to payout $${(selectedSeller.balanceCents / 100).toFixed(2)} ${selectedSeller.currency} to seller ${selectedSeller.sellerId}?`
            : ''
        }
      />

      <Dialog
        open={payoutsDialogOpen}
        onClose={() => setPayoutsDialogOpen(false)}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle>
          Payouts for {selectedSeller?.sellerId || 'Seller'}
        </DialogTitle>
        <DialogContent>
          {payoutsLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
              <CircularProgress />
            </Box>
          ) : selectedSellerPayouts.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              No payouts found for this seller
            </Typography>
          ) : (
            <TableContainer>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Payout ID</TableCell>
                    <TableCell>Amount</TableCell>
                    <TableCell>Currency</TableCell>
                    <TableCell>State</TableCell>
                    <TableCell>Created</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {selectedSellerPayouts.map((payout) => (
                    <TableRow key={payout.id}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {payout.id.substring(0, 8)}...
                        </Typography>
                      </TableCell>
                      <TableCell>${(payout.amountCents / 100).toFixed(2)}</TableCell>
                      <TableCell>{payout.currency}</TableCell>
                      <TableCell>
                        <Chip
                          label={payout.state}
                          color={
                            payout.state === 'COMPLETED'
                              ? 'success'
                              : payout.state === 'FAILED'
                              ? 'error'
                              : 'default'
                          }
                          size="small"
                        />
                      </TableCell>
                      <TableCell>
                        {new Date(payout.createdAt).toLocaleString()}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPayoutsDialogOpen(false)}>Close</Button>
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

export default AdminPayouts

