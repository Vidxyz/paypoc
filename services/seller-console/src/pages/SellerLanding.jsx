import { useState, useEffect } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Box,
  Grid,
  CircularProgress,
  Alert,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,
} from '@mui/material'
import StoreIcon from '@mui/icons-material/Store'
import AccountBalanceIcon from '@mui/icons-material/AccountBalance'
import PaymentIcon from '@mui/icons-material/Payment'
import { useAuth0 } from '../auth/Auth0Provider'
import { createPaymentsApiClient } from '../api/paymentsApi'

function SellerLanding() {
  const { user, isAuthenticated, getAccessToken } = useAuth0()
  const userEmail = user?.email || user?.['https://buyit.local/email'] || ''
  const [balance, setBalance] = useState(null)
  const [payments, setPayments] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (!isAuthenticated) {
      return
    }

    const fetchData = async () => {
      try {
        setLoading(true)
        setError(null)

        const paymentsApi = createPaymentsApiClient(getAccessToken)

        // Fetch balance and payments in parallel
        const [balanceData, paymentsData] = await Promise.all([
          paymentsApi.getSellerBalance().catch((err) => {
            console.warn('Failed to fetch balance:', err)
            return { balanceCents: 0, currency: 'USD', error: err.message }
          }),
          paymentsApi.getSellerPayments({ page: 0, size: 10, sortBy: 'createdAt', sortDirection: 'DESC' }).catch((err) => {
            console.warn('Failed to fetch payments:', err)
            return { payments: [], total: 0 }
          }),
        ])

        setBalance(balanceData)
        setPayments(paymentsData.payments || [])

        paymentsApi.cleanup()
      } catch (err) {
        console.error('Failed to fetch seller data:', err)
        setError(err.message || 'Failed to load seller information')
      } finally {
        setLoading(false)
      }
    }

    fetchData()
  }, [isAuthenticated, getAccessToken])

  const formatAmount = (cents, currency = 'USD') => {
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

  const getStateColor = (state) => {
    switch (state) {
      case 'CAPTURED':
        return 'success'
      case 'AUTHORIZED':
        return 'info'
      case 'CREATED':
      case 'CONFIRMING':
        return 'warning'
      case 'FAILED':
        return 'error'
      default:
        return 'default'
    }
  }

  if (loading) {
    return (
      <Container maxWidth="lg" sx={{ py: 4, display: 'flex', justifyContent: 'center' }}>
        <CircularProgress />
      </Container>
    )
  }

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Box sx={{ mb: 4, textAlign: 'center' }}>
        <StoreIcon sx={{ fontSize: 64, color: 'primary.main', mb: 2 }} />
        <Typography variant="h3" component="h1" gutterBottom>
          Welcome to Seller Console
        </Typography>
        <Typography variant="h6" color="text.secondary">
          {userEmail && `Logged in as: ${userEmail}`}
        </Typography>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {error}
        </Alert>
      )}

      {/* Account Balance Card */}
      <Grid container spacing={3} sx={{ mb: 4 }}>
        <Grid item xs={12} md={6}>
          <Card>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
                <AccountBalanceIcon sx={{ fontSize: 48, color: 'primary.main', mr: 2 }} />
                <Box>
                  <Typography variant="h6" color="text.secondary">
                    Account Balance
                  </Typography>
                  {balance ? (
                    <>
                      <Typography variant="h4" component="div" sx={{ fontWeight: 'bold' }}>
                        {formatAmount(balance.balanceCents, balance.currency)}
                      </Typography>
                      {balance.error && (
                        <Typography variant="caption" color="error">
                          {balance.error}
                        </Typography>
                      )}
                    </>
                  ) : (
                    <Typography variant="body2" color="text.secondary">
                      Loading...
                    </Typography>
                  )}
                </Box>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={6}>
          <Card>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
                <PaymentIcon sx={{ fontSize: 48, color: 'primary.main', mr: 2 }} />
                <Box>
                  <Typography variant="h6" color="text.secondary">
                    Total Transactions
                  </Typography>
                  <Typography variant="h4" component="div" sx={{ fontWeight: 'bold' }}>
                    {payments.length}
                  </Typography>
                </Box>
              </Box>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Recent Transactions */}
      <Card sx={{ mb: 4 }}>
        <CardContent>
          <Typography variant="h5" component="h2" gutterBottom>
            Recent Transactions
          </Typography>
          {payments.length === 0 ? (
            <Alert severity="info">No transactions found.</Alert>
          ) : (
            <TableContainer component={Paper} variant="outlined">
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Payment ID</TableCell>
                    <TableCell>Date</TableCell>
                    <TableCell>Buyer ID</TableCell>
                    <TableCell>Amount</TableCell>
                    <TableCell>Net Amount</TableCell>
                    <TableCell>State</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {payments.map((payment) => (
                    <TableRow key={payment.id}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {payment.id.substring(0, 8)}...
                        </Typography>
                      </TableCell>
                      <TableCell>{formatDate(payment.createdAt)}</TableCell>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {payment.buyerId?.substring(0, 12)}...
                        </Typography>
                      </TableCell>
                      <TableCell>{formatAmount(payment.grossAmountCents, payment.currency)}</TableCell>
                      <TableCell>{formatAmount(payment.netSellerAmountCents, payment.currency)}</TableCell>
                      <TableCell>
                        <Chip
                          label={payment.state}
                          color={getStateColor(payment.state)}
                          size="small"
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>
    </Container>
  )
}

export default SellerLanding

