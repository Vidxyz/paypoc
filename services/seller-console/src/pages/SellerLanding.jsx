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
  Button,
  Pagination,
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
  const [transactions, setTransactions] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadingPayments, setLoadingPayments] = useState(false) // Separate loading state for pagination
  const [error, setError] = useState(null)
  const [payoutsPage, setPayoutsPage] = useState(0)
  const [payoutsPageSize] = useState(20)
  const [payoutsTotal, setPayoutsTotal] = useState(0)
  const [payoutsTotalPages, setPayoutsTotalPages] = useState(0)
  const [paymentsPage, setPaymentsPage] = useState(0)
  const [paymentsPageSize] = useState(20)
  const [paymentsTotal, setPaymentsTotal] = useState(0)
  const [paymentsTotalPages, setPaymentsTotalPages] = useState(0)
  const [payments, setPayments] = useState([]) // Store paginated payments from server
  const [payouts, setPayouts] = useState([]) // Store paginated payouts from server

  useEffect(() => {
    if (!isAuthenticated) {
      return
    }

    const fetchData = async () => {
      try {
        // Only show full page loading on initial load, use separate state for pagination
        if (paymentsPage === 0 && payoutsPage === 0) {
          setLoading(true)
        } else {
          setLoadingPayments(true)
        }
        setError(null)

        const paymentsApi = createPaymentsApiClient(getAccessToken)

        // Fetch balance, payments, and payouts in parallel
        const [balanceData, paymentsData, payoutsData] = await Promise.all([
          paymentsApi.getSellerBalance().catch((err) => {
            console.warn('Failed to fetch balance:', err)
            return { balanceCents: 0, currency: 'CAD', error: err.message }
          }),
          paymentsApi.getSellerPayments({ page: paymentsPage, size: paymentsPageSize, sortBy: 'createdAt', sortDirection: 'DESC' }).catch((err) => {
            console.warn('Failed to fetch payments:', err)
            return { payments: [], total: 0, page: paymentsPage, size: paymentsPageSize }
          }),
          paymentsApi.getSellerPayouts({ page: payoutsPage, size: payoutsPageSize }).catch((err) => {
            console.warn('Failed to fetch payouts:', err)
            return { payouts: [], total: 0, totalPages: 0, page: 0, size: payoutsPageSize }
          }),
        ])

        setBalance(balanceData)
        
        // Set paginated payments from server
        const fetchedPayments = paymentsData.payments || []
        setPayments(fetchedPayments)
        setPaymentsTotal(paymentsData.total || 0)
        // Calculate total pages for payments
        const paymentsTotalPages = paymentsData.total > 0 ? Math.ceil((paymentsData.total || 0) / paymentsPageSize) : 0
        setPaymentsTotalPages(paymentsTotalPages)
        
        // Set paginated payouts from server
        const fetchedPayouts = payoutsData.payouts || []
        setPayouts(fetchedPayouts)
        setPayoutsTotal(payoutsData.total || 0)
        setPayoutsTotalPages(payoutsData.totalPages || 0)

        // Fetch refunds for current page of payments in a single batch call
        // Process current page of payments to build transactions list
        const paymentsList = fetchedPayments
        console.log(`[SellerLanding] Processing ${paymentsList.length} payments for page ${paymentsPage}, userEmail: ${userEmail}`)
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
          if (!sellerBreakdown) {
            console.log(`[SellerLanding] Payment ${payment.id} has no seller breakdown for ${userEmail}. Available sellers:`, payment.sellerBreakdown?.map(sb => sb.sellerId))
            return // Skip if seller not in this payment
          }
          
          // Add payment as CREDIT transaction
          // Include all states except FAILED - even CONFIRMING/AUTHORIZED should be visible
          if (payment.state !== 'FAILED') {
            transactionsList.push({
              id: payment.id,
              type: 'CREDIT',
              date: payment.createdAt,
              description: `Payment from order ${payment.orderId?.substring(0, 8) || 'N/A'}... (${payment.state})`,
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
        
        const creditCount = transactionsWithBalance.filter(t => t.type === 'CREDIT').length
        const debitCount = transactionsWithBalance.filter(t => t.type === 'DEBIT').length
        console.log(`[SellerLanding] Built ${transactionsWithBalance.length} transactions (${creditCount} CREDIT, ${debitCount} DEBIT) from ${paymentsList.length} payments on page ${paymentsPage}`)
        setTransactions(transactionsWithBalance)

        paymentsApi.cleanup()
      } catch (err) {
        console.error('Failed to fetch seller data:', err)
        setError(err.message || 'Failed to load seller information')
      } finally {
        setLoading(false)
        setLoadingPayments(false)
      }
    }

    fetchData()
  }, [isAuthenticated, getAccessToken, payoutsPage, paymentsPage])

  const handleTransactionsPageChange = (event, value) => {
    setPaymentsPage(value - 1) // Use paymentsPage for server-side pagination
  }

  const handlePayoutsPageChange = (event, value) => {
    setPayoutsPage(value - 1) // MUI Pagination is 1-indexed, we use 0-indexed
    // Data will be refetched via useEffect dependency on payoutsPage
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
                       {paymentsTotal}
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
          {loadingPayments && (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
              <CircularProgress size={24} />
            </Box>
          )}
          {!loadingPayments && transactions.length === 0 ? (
            <Alert severity="info">No transactions found.</Alert>
          ) : (
            <TableContainer component={Paper} variant="outlined" sx={{ opacity: loadingPayments ? 0.5 : 1 }}>
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
                  {transactions.map((transaction) => (
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

          {/* Transactions Pagination - based on payments pagination */}
          {paymentsTotalPages > 1 && (
            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2, mt: 3 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                <Typography variant="body2" color="text.secondary">
                  Page {paymentsPage + 1} of {paymentsTotalPages} ({paymentsTotal} total payments)
                </Typography>
                <Pagination
                  count={paymentsTotalPages}
                  page={paymentsPage + 1}
                  onChange={(event, value) => setPaymentsPage(value - 1)}
                  color="primary"
                  showFirstButton
                  showLastButton
                />
              </Box>
            </Box>
          )}
          
          {/* Show total count if no pagination needed */}
          {paymentsTotalPages <= 1 && paymentsTotal > 0 && (
            <Box sx={{ display: 'flex', justifyContent: 'center', mt: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Showing all {paymentsTotal} payments
              </Typography>
            </Box>
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
          {payouts.length === 0 && payoutsTotal === 0 ? (
            <Alert severity="info">No payouts found.</Alert>
          ) : (
            <>
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

              {/* Payouts Pagination */}
              {payoutsTotalPages > 1 && (
                <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2, mt: 3 }}>
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
                </Box>
              )}
              
              {/* Show total count if no pagination needed */}
              {payoutsTotalPages <= 1 && payoutsTotal > 0 && (
                <Box sx={{ display: 'flex', justifyContent: 'center', mt: 3 }}>
                  <Typography variant="body2" color="text.secondary">
                    Showing all {payoutsTotal} payout{payoutsTotal !== 1 ? 's' : ''}
                  </Typography>
                </Box>
              )}
            </>
          )}
        </CardContent>
      </Card>
    </Container>
  )
}

export default SellerLanding

