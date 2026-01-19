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
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import { useAuth0 } from '../auth/Auth0Provider'
import { createPaymentsApiClient } from '../api/paymentsApi'

function SellerLanding() {
  const { user, isAuthenticated, getAccessToken } = useAuth0()
  const userEmail = user?.email || user?.['https://buyit.local/email'] || ''
  const [balance, setBalance] = useState(null)
  const [payments, setPayments] = useState([])
  const [payouts, setPayouts] = useState([])
  const [transactions, setTransactions] = useState([])
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

        // Fetch balance, payments, and payouts in parallel
        const [balanceData, paymentsData, payoutsData] = await Promise.all([
          paymentsApi.getSellerBalance().catch((err) => {
            console.warn('Failed to fetch balance:', err)
            return { balanceCents: 0, currency: 'CAD', error: err.message }
          }),
          paymentsApi.getSellerPayments({ page: 0, size: 50, sortBy: 'createdAt', sortDirection: 'DESC' }).catch((err) => {
            console.warn('Failed to fetch payments:', err)
            return { payments: [], total: 0 }
          }),
          paymentsApi.getSellerPayouts().catch((err) => {
            console.warn('Failed to fetch payouts:', err)
            return { payouts: [] }
          }),
        ])

        setBalance(balanceData)
        setPayments(paymentsData.payments || [])
        setPayouts(payoutsData.payouts || [])

        // Fetch refunds for all payments in a single batch call
        const paymentsList = paymentsData.payments || []
        const paymentIds = paymentsList.map(p => p.id)
        const refundsMap = await paymentsApi.getRefundsForPayments(paymentIds).catch(() => ({}))
        
        // Map refunds back to payments for easier processing
        const refundResults = paymentsList.map(payment => ({
          refunds: refundsMap[payment.id] || []
        }))
        
        // Build combined transactions list
        const transactionsList = []
        
        // Add payments as CREDIT transactions
        paymentsList.forEach((payment, index) => {
          const refunds = refundResults[index]?.refunds || []
          
          // Find this seller's portion in the payment
          const sellerBreakdown = payment.sellerBreakdown?.find(sb => sb.sellerId === userEmail)
          if (!sellerBreakdown) return // Skip if seller not in this payment
          
          // Add payment as CREDIT (only if CAPTURED)
          if (payment.state === 'CAPTURED' || payment.state === 'REFUNDING' || payment.state === 'REFUNDED') {
            transactionsList.push({
              id: payment.id,
              type: 'CREDIT',
              date: payment.createdAt,
              description: `Payment from order ${payment.orderId?.substring(0, 8) || 'N/A'}...`,
              amountCents: sellerBreakdown.netSellerAmountCents,
              currency: payment.currency,
              paymentId: payment.id,
              orderId: payment.orderId,
              state: payment.state,
            })
          }
          
          // Add refunds as DEBIT transactions
          refunds.forEach(refund => {
            if (refund.state === 'REFUNDED' || refund.state === 'REFUNDING') {
              // Find this seller's portion in the refund
              const sellerRefundBreakdown = refund.sellerRefundBreakdown?.find(srb => srb.sellerId === userEmail)
              
              // If sellerRefundBreakdown is not available (old refunds), calculate proportionally
              // This is not accurate for partial refunds, but better than nothing
              let sellerRefundAmount = 0
              if (sellerRefundBreakdown) {
                sellerRefundAmount = sellerRefundBreakdown.netSellerRefundCents
              } else if (sellerBreakdown) {
                // Fallback: calculate proportionally based on payment breakdown
                // This assumes the refund is proportional, which may not be true for partial refunds
                const sellerPaymentRatio = sellerBreakdown.netSellerAmountCents / payment.netSellerAmountCents
                sellerRefundAmount = Math.round(refund.netSellerRefundCents * sellerPaymentRatio)
              }
              
              if (sellerRefundAmount > 0) {
                transactionsList.push({
                  id: refund.id,
                  type: 'DEBIT',
                  date: refund.refundedAt || refund.updatedAt || refund.createdAt,
                  description: refund.state === 'REFUNDING' 
                    ? `Refund processing for payment ${payment.id.substring(0, 8)}...`
                    : `Refund for payment ${payment.id.substring(0, 8)}...`,
                  amountCents: sellerRefundAmount,
                  currency: payment.currency,
                  paymentId: payment.id,
                  refundId: refund.id,
                  orderId: payment.orderId,
                  state: refund.state,
                })
              }
            }
          })
        })
        
        // Sort transactions by date (oldest first for balance calculation)
        transactionsList.sort((a, b) => new Date(a.date) - new Date(b.date))
        
        // Calculate running balance for each transaction
        let runningBalance = 0
        const transactionsWithBalance = transactionsList.map(transaction => {
          if (transaction.type === 'CREDIT') {
            runningBalance += transaction.amountCents
          } else {
            runningBalance -= transaction.amountCents
          }
          return {
            ...transaction,
            runningBalance,
          }
        })
        
        // Sort back to newest first for display
        transactionsWithBalance.sort((a, b) => new Date(b.date) - new Date(a.date))
        
        setTransactions(transactionsWithBalance)

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

  const getPayoutStateColor = (state) => {
    switch (state) {
      case 'COMPLETED':
        return 'success'
      case 'PROCESSING':
        return 'info'
      case 'PENDING':
        return 'warning'
      case 'FAILED':
        return 'error'
      default:
        return 'default'
    }
  }

  if (loading) {
    return (
      <Container maxWidth="lg" sx={{ py: { xs: 3, sm: 4, md: 5 }, display: 'flex', justifyContent: 'center' }}>
        <CircularProgress />
      </Container>
    )
  }

  return (
    <Container maxWidth="lg" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <Box sx={{ mb: 4, textAlign: 'center' }}>
        <StoreIcon sx={{ fontSize: 64, color: 'primary.main', mb: 2 }} />
        <Typography 
          variant="h3" 
          component="h1" 
          gutterBottom
          sx={{ 
            fontWeight: 700,
            background: 'linear-gradient(135deg, #4a90e2 0%, #3a7bc8 100%)',
            backgroundClip: 'text',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
          }}
        >
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
          {transactions.length === 0 ? (
            <Alert severity="info">No transactions found.</Alert>
          ) : (
            <TableContainer component={Paper} variant="outlined">
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Date</TableCell>
                    <TableCell>Type</TableCell>
                    <TableCell>Description</TableCell>
                    <TableCell align="right">Amount</TableCell>
                    <TableCell align="right">Running Balance</TableCell>
                    <TableCell>Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {transactions.slice(0, 20).map((transaction) => (
                    <TableRow key={`${transaction.type}-${transaction.id}`}>
                      <TableCell>{formatDate(transaction.date)}</TableCell>
                      <TableCell>
                        <Chip
                          label={transaction.type}
                          color={transaction.type === 'CREDIT' ? 'success' : 'error'}
                          size="small"
                          variant="outlined"
                        />
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {transaction.description}
                        </Typography>
                        {transaction.orderId && (
                          <Typography variant="caption" color="text.secondary" display="block">
                            Order: {transaction.orderId.substring(0, 8)}...
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell align="right">
                        <Typography
                          variant="body2"
                          fontWeight="bold"
                          color={transaction.type === 'CREDIT' ? 'success.main' : 'error.main'}
                        >
                          {transaction.type === 'CREDIT' ? '+' : '-'}
                          {formatAmount(transaction.amountCents, transaction.currency)}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="body2" color="text.secondary">
                          {formatAmount(transaction.runningBalance, transaction.currency)}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={transaction.state}
                          color={transaction.state === 'CAPTURED' || transaction.state === 'REFUNDED' ? 'success' : 
                                 transaction.state === 'REFUNDING' ? 'warning' : 'default'}
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

      {/* Payouts */}
      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
            <AccountBalanceWalletIcon sx={{ fontSize: 32, color: 'primary.main', mr: 1 }} />
            <Typography variant="h5" component="h2">
              Payouts
            </Typography>
          </Box>
          {payouts.length === 0 ? (
            <Alert severity="info">No payouts found.</Alert>
          ) : (
            <TableContainer component={Paper} variant="outlined">
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Payout ID</TableCell>
                    <TableCell>Date</TableCell>
                    <TableCell>Amount</TableCell>
                    <TableCell>State</TableCell>
                    <TableCell>Stripe Transfer ID</TableCell>
                    <TableCell>Completed At</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {payouts.map((payout) => (
                    <TableRow key={payout.id}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {payout.id?.substring(0, 8)}...
                        </Typography>
                      </TableCell>
                      <TableCell>{formatDate(payout.createdAt)}</TableCell>
                      <TableCell>{formatAmount(payout.amountCents, payout.currency)}</TableCell>
                      <TableCell>
                        <Chip
                          label={payout.state}
                          color={getPayoutStateColor(payout.state)}
                          size="small"
                        />
                      </TableCell>
                      <TableCell>
                        {payout.stripeTransferId ? (
                          <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                            {payout.stripeTransferId.substring(0, 12)}...
                          </Typography>
                        ) : (
                          <Typography variant="body2" color="text.secondary">
                            N/A
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>{formatDate(payout.completedAt)}</TableCell>
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

