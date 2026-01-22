import { useState, useEffect } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
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
  Pagination,
} from '@mui/material'
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import { useAuth0 } from '../auth/Auth0Provider'
import { createPaymentsApiClient } from '../api/paymentsApi'

function Payouts() {
  const { getAccessToken } = useAuth0()
  const [payouts, setPayouts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [payoutsPage, setPayoutsPage] = useState(0)
  const [payoutsPageSize] = useState(20)
  const [payoutsTotal, setPayoutsTotal] = useState(0)
  const [payoutsTotalPages, setPayoutsTotalPages] = useState(0)
  const [paymentsApiClient, setPaymentsApiClient] = useState(null)

  useEffect(() => {
    const client = createPaymentsApiClient(getAccessToken)
    setPaymentsApiClient(() => client)
    
    return () => {
      if (client.cleanup) client.cleanup()
    }
  }, [getAccessToken])

  useEffect(() => {
    if (paymentsApiClient) {
      loadPayouts()
    }
  }, [paymentsApiClient, payoutsPage])

  const loadPayouts = async () => {
    if (!paymentsApiClient) return
    
    setLoading(true)
    setError('')
    
    try {
      const data = await paymentsApiClient.getSellerPayouts({ page: payoutsPage, size: payoutsPageSize })
      
      setPayouts(data.payouts || [])
      setPayoutsTotal(data.total || 0)
      setPayoutsTotalPages(data.totalPages || 0)
    } catch (err) {
      if (err.response?.status === 401) {
        setError('Unauthorized. Please log in again.')
      } else {
        setError(err.message || 'Failed to load payouts')
      }
    } finally {
      setLoading(false)
    }
  }

  const handlePayoutsPageChange = (event, value) => {
    setPayoutsPage(value - 1) // MUI Pagination is 1-indexed, we use 0-indexed
  }

  const formatAmount = (cents, currency = 'CAD') => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency,
    }).format(cents / 100)
  }

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A'
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const getPayoutStateColor = (state) => {
    switch (state) {
      case 'COMPLETED':
        return 'success' // Green
      case 'PROCESSING':
      case 'CONFIRMING':
        return 'warning' // Yellow
      case 'PENDING':
        return 'info'
      case 'FAILED':
        return 'error'
      default:
        return 'default'
    }
  }

  if (loading && payouts.length === 0) {
    return (
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Card>
          <CardContent sx={{ textAlign: 'center', py: 8 }}>
            <CircularProgress />
            <Typography variant="body1" color="text.secondary" sx={{ mt: 2 }}>
              Loading payouts...
            </Typography>
          </CardContent>
        </Card>
      </Container>
    )
  }

  return (
    <Container maxWidth="lg" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <Card>
        <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 4, flexWrap: 'wrap', gap: 2 }}>
            <Box>
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
                  mb: 1,
                }}
              >
                Payouts
              </Typography>
              <Typography variant="body1" color="text.secondary">
                View your payout history
              </Typography>
            </Box>
          </Box>

          {error && (
            <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
              {error}
            </Alert>
          )}

          {payouts.length === 0 && payoutsTotal === 0 ? (
            <Box sx={{ textAlign: 'center', py: 8 }}>
              <AccountBalanceWalletIcon sx={{ fontSize: 64, color: 'text.secondary', mb: 2 }} />
              <Typography variant="h6" color="text.secondary" gutterBottom>
                No payouts found
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Payouts will appear here once they are processed.
              </Typography>
            </Box>
          ) : (
            <>
              <TableContainer component={Paper} variant="outlined">
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell><strong>Payout ID</strong></TableCell>
                      <TableCell><strong>Date</strong></TableCell>
                      <TableCell><strong>Amount</strong></TableCell>
                      <TableCell><strong>State</strong></TableCell>
                      <TableCell><strong>Stripe Transfer ID</strong></TableCell>
                      <TableCell><strong>Completed At</strong></TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {payouts.map((payout) => (
                      <TableRow key={payout.id} hover>
                        <TableCell>
                          <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                            {payout.id?.substring(0, 8)}...
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2">
                            {formatDate(payout.createdAt)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" fontWeight="bold">
                            {formatAmount(payout.amountCents, payout.currency)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Chip
                            label={payout.state}
                            color={getPayoutStateColor(payout.state)}
                            size="small"
                          />
                        </TableCell>
                        <TableCell>
                          {payout.stripeTransferId ? (
                            <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                              {payout.stripeTransferId.substring(0, 12)}...
                            </Typography>
                          ) : (
                            <Typography variant="body2" color="text.secondary">
                              N/A
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2">
                            {formatDate(payout.completedAt)}
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>

              {/* Payouts Pagination */}
              <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2, mt: 3 }}>
                {payoutsTotalPages > 1 && (
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                    <Typography variant="body2" color="text.secondary">
                      Page {payoutsPage + 1} of {payoutsTotalPages} ({payoutsTotal} total payouts)
                    </Typography>
                    <Pagination
                      count={payoutsTotalPages}
                      page={payoutsPage + 1}
                      onChange={handlePayoutsPageChange}
                      color="primary"
                      showFirstButton
                      showLastButton
                    />
                  </Box>
                )}
                
                {/* Show total count if no pagination needed */}
                {payoutsTotalPages <= 1 && payoutsTotal > 0 && (
                  <Typography variant="body2" color="text.secondary">
                    Showing all {payoutsTotal} payout{payoutsTotal !== 1 ? 's' : ''}
                  </Typography>
                )}
              </Box>
            </>
          )}
        </CardContent>
      </Card>
    </Container>
  )
}

export default Payouts
