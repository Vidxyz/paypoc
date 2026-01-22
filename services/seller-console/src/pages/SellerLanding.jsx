import { useState, useEffect, useRef } from 'react'
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
  const [paymentsPage, setPaymentsPage] = useState(0)
  const [paymentsPageSize] = useState(20)
  const [paymentsTotal, setPaymentsTotal] = useState(0)
  const [paymentsTotalPages, setPaymentsTotalPages] = useState(0)
  const [payments, setPayments] = useState([]) // Store paginated payments from server
  const [allPayouts, setAllPayouts] = useState([]) // Store all payouts (fetched once, reused)
  const [previousPageBalance, setPreviousPageBalance] = useState(null) // Balance at the end of previous page (for pagination)
  const payoutsFetchedRef = useRef(false) // Track if we've already fetched payouts (using ref to avoid re-renders)

  useEffect(() => {
    if (!isAuthenticated) {
      return
    }

    const fetchData = async () => {
      try {
        // Only show full page loading on initial load, use separate state for pagination
        if (paymentsPage === 0) {
          setLoading(true)
        } else {
          setLoadingPayments(true)
        }
        setError(null)

        const paymentsApi = createPaymentsApiClient(getAccessToken)

        // Fetch balance and payments in parallel
        const [balanceData, paymentsData] = await Promise.all([
          paymentsApi.getSellerBalance().catch((err) => {
            console.warn('Failed to fetch balance:', err)
            return { balanceCents: 0, currency: 'CAD', error: err.message }
          }),
          paymentsApi.getSellerPayments({ page: paymentsPage, size: paymentsPageSize, sortBy: 'createdAt', sortDirection: 'DESC' }).catch((err) => {
            console.warn('Failed to fetch payments:', err)
            return { payments: [], total: 0, page: paymentsPage, size: paymentsPageSize }
          }),
        ])
        
        // todo-vh: There might be a small bug in running balances, might be caused by missing ledger entries for payments. Need to test afresh
        // todo-vh: We need a better way to fetch payouts than this
        // Fetch all payouts if we don't have them yet (only once, on first load)
        let payoutsToUse = allPayouts
        if (!payoutsFetchedRef.current && paymentsPage === 0) {
          const payoutsData = await paymentsApi.getSellerPayouts({ page: 0, size: 1000 }).catch((err) => {
            console.warn('Failed to fetch payouts:', err)
            return { payouts: [], total: 0 }
          })
          const fetchedPayouts = payoutsData.payouts || []
          setAllPayouts(fetchedPayouts)
          payoutsFetchedRef.current = true
          payoutsToUse = fetchedPayouts
        }

        setBalance(balanceData)
        
        // Set paginated payments from server
        const fetchedPayments = paymentsData.payments || []
        setPayments(fetchedPayments)
        setPaymentsTotal(paymentsData.total || 0)
        // Calculate total pages for payments
        const paymentsTotalPages = paymentsData.total > 0 ? Math.ceil((paymentsData.total || 0) / paymentsPageSize) : 0
        setPaymentsTotalPages(paymentsTotalPages)

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
        
        // Build combined transactions list (payments, refunds, and payouts)
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
          // But only CAPTURED and REFUNDED affect balance calculation
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
              affectsBalance: payment.state === 'CAPTURED' || payment.state === 'REFUNDED', // Only these affect balance
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
                  affectsBalance: refund.state === 'REFUNDED', // Only REFUNDED affects balance
                })
              }
            }
          })
        })
        
        // Add payouts as DEBIT transactions (they reduce the balance)
        // Interleave payouts that fall within the date range of current page payments
        // Find the date range of current page payments (only those that affect balance for accurate interleaving)
        // Note: We use all payment dates for date range, but only transactions that affect balance will affect the balance calculation
        const paymentDates = transactionsList.map(t => new Date(t.date))
        const minPaymentDate = paymentDates.length > 0 ? Math.min(...paymentDates.map(d => d.getTime())) : null
        const maxPaymentDate = paymentDates.length > 0 ? Math.max(...paymentDates.map(d => d.getTime())) : null
        
        payoutsToUse.forEach(payout => {
          // Show all payouts (COMPLETED, PROCESSING, etc.) but only COMPLETED affects balance
          if (payout.state === 'COMPLETED' || payout.state === 'PROCESSING' || payout.state === 'PENDING') {
            const payoutDate = new Date(payout.completedAt || payout.createdAt).getTime()
            // Include payout if:
            // 1. We have no payment date range (first page with no payments yet), OR
            // 2. Payout falls within the date range of current page payments
            if (minPaymentDate === null || maxPaymentDate === null || 
                (payoutDate >= minPaymentDate && payoutDate <= maxPaymentDate)) {
              transactionsList.push({
                id: payout.id,
                type: 'DEBIT',
                date: payout.completedAt || payout.createdAt,
                description: `Payout ${payout.id?.substring(0, 8) || 'N/A'}... (${payout.state})`,
                amountCents: payout.amountCents,
                currency: payout.currency,
                payoutId: payout.id,
                state: payout.state,
                affectsBalance: payout.state === 'COMPLETED', // Only COMPLETED affects balance
              })
            }
          }
        })
        
        // Sort transactions by date (newest first) - we'll calculate balance backwards
        transactionsList.sort((a, b) => new Date(b.date) - new Date(a.date))
        
        // Separate transactions that affect balance from those that don't
        const transactionsAffectingBalance = transactionsList.filter(t => t.affectsBalance)
        const transactionsNotAffectingBalance = transactionsList.filter(t => !t.affectsBalance)
        
        console.log(`[SellerLanding] Page ${paymentsPage}: ${transactionsAffectingBalance.length} transactions affect balance, ${transactionsNotAffectingBalance.length} do not`)
        if (transactionsNotAffectingBalance.length > 0) {
          console.log(`[SellerLanding] Transactions NOT affecting balance:`, transactionsNotAffectingBalance.map(t => ({ 
            id: t.id?.substring(0, 8), 
            type: t.type, 
            state: t.state, 
            amount: t.amountCents 
          })))
        }
        
        // Calculate running balance backwards from account balance
        // Start with account balance (or previous page balance if on page > 0)
        // IMPORTANT: previousPageBalance should only account for transactions that affect balance
        const startingBalance = paymentsPage === 0 
          ? (balanceData.balanceCents || 0)
          : (previousPageBalance !== null ? previousPageBalance : balanceData.balanceCents || 0)
        
        console.log(`[SellerLanding] Page ${paymentsPage}: Starting balance: ${startingBalance} (from ${paymentsPage === 0 ? 'account balance' : 'previous page'})`)
        
        // First, calculate balance backwards using ONLY transactions that affect balance
        // This gives us the correct balance at each point in time
        let currentBalance = startingBalance
        const balanceMap = new Map() // Map transaction ID to balance after that transaction
        
        // Process transactions that affect balance (newest to oldest)
        transactionsAffectingBalance.forEach(transaction => {
          // The balance AFTER this transaction is the current balance
          balanceMap.set(transaction.id, currentBalance)
          
          // Calculate balance BEFORE this transaction
          // CREDIT (payment): balance before = current balance - amount (payment increased balance)
          // DEBIT (refund/payout): balance before = current balance + amount (debit decreased balance)
          const balanceBefore = currentBalance
          if (transaction.type === 'CREDIT') {
            currentBalance = currentBalance - transaction.amountCents
          } else {
            currentBalance = currentBalance + transaction.amountCents
          }
          console.log(`[SellerLanding] Transaction ${transaction.id?.substring(0, 8)} (${transaction.type}, ${transaction.state}) affects balance: ${balanceBefore} -> ${currentBalance}`)
        })
        
        // Store the last balance (oldest transaction's balance before) for next page
        // This is the balance that existed before the oldest transaction on this page that affects balance
        const endingBalance = currentBalance
        setPreviousPageBalance(endingBalance)
        console.log(`[SellerLanding] Page ${paymentsPage}: Ending balance for next page: ${endingBalance}`)
        
        // Now assign running balances to ALL transactions (including those that don't affect balance)
        // For transactions that affect balance, use the calculated balance
        // For transactions that don't affect balance, use the balance of the next transaction that affects balance
        const transactionsWithBalance = transactionsList.map(transaction => {
          let runningBalance
          
          if (transaction.affectsBalance) {
            // Use the calculated balance from balanceMap
            runningBalance = balanceMap.get(transaction.id) || startingBalance
          } else {
            // Find the next transaction that affects balance (newer than this one)
            // and use its balance, or use startingBalance if this is the newest
            const transactionIndex = transactionsList.findIndex(t => t.id === transaction.id)
            const nextAffectingTransaction = transactionsList
              .slice(0, transactionIndex)
              .reverse()
              .find(t => t.affectsBalance)
            
            if (nextAffectingTransaction) {
              runningBalance = balanceMap.get(nextAffectingTransaction.id) || startingBalance
            } else {
              // This is the newest transaction and it doesn't affect balance
              // Use the starting balance
              runningBalance = startingBalance
            }
            
            console.log(`[SellerLanding] Transaction ${transaction.id?.substring(0, 8)} (${transaction.type}, ${transaction.state}) does NOT affect balance, using balance: ${runningBalance}`)
          }
          
          return {
            ...transaction,
            runningBalance,
          }
        })
        
        // Verification: For page 0, the newest transaction's running balance should equal account balance
        if (paymentsPage === 0 && transactionsWithBalance.length > 0) {
          const newestTransaction = transactionsWithBalance[0]
          const newestBalance = newestTransaction.runningBalance
          const accountBalance = balanceData.balanceCents || 0
          
          if (newestTransaction.affectsBalance && newestBalance !== accountBalance) {
            console.warn(`[SellerLanding] WARNING: Newest transaction balance (${newestBalance}) does not match account balance (${accountBalance})`)
          } else if (newestTransaction.affectsBalance) {
            console.log(`[SellerLanding] ✓ Newest transaction balance matches account balance: ${newestBalance}`)
          } else {
            // Newest transaction doesn't affect balance, find the next one that does
            const nextAffecting = transactionsWithBalance.find(t => t.affectsBalance)
            if (nextAffecting && nextAffecting.runningBalance !== accountBalance) {
              console.warn(`[SellerLanding] WARNING: Next affecting transaction balance (${nextAffecting.runningBalance}) does not match account balance (${accountBalance})`)
            }
          }
        }
        
        // Verify ending balance makes sense
        if (transactionsAffectingBalance.length === 0) {
          // No transactions affect balance on this page, ending balance should equal starting balance
          if (endingBalance !== startingBalance) {
            console.warn(`[SellerLanding] WARNING: No transactions affect balance, but ending balance (${endingBalance}) != starting balance (${startingBalance})`)
          } else {
            console.log(`[SellerLanding] ✓ No transactions affect balance, ending balance equals starting balance: ${endingBalance}`)
          }
        }
        
        const creditCount = transactionsWithBalance.filter(t => t.type === 'CREDIT').length
        const debitCount = transactionsWithBalance.filter(t => t.type === 'DEBIT' && !t.payoutId).length
        const payoutCount = transactionsWithBalance.filter(t => t.payoutId).length
        console.log(`[SellerLanding] Page ${paymentsPage}: Built ${transactionsWithBalance.length} transactions (${creditCount} CREDIT, ${debitCount} DEBIT refunds, ${payoutCount} payouts) from ${paymentsList.length} payments`)
        console.log(`[SellerLanding] Balance calculation: Starting balance: ${startingBalance}, Ending balance (for next page): ${currentBalance}`)
        if (transactionsWithBalance.length > 0) {
          console.log(`[SellerLanding] Newest transaction balance: ${transactionsWithBalance[0].runningBalance}, Account balance: ${balanceData.balanceCents}`)
        }
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
  }, [isAuthenticated, getAccessToken, paymentsPage])

  const handleTransactionsPageChange = (event, value) => {
    const newPage = value - 1
    // If going to page 0, reset previous page balance and refetch payouts if needed
    if (newPage === 0) {
      setPreviousPageBalance(null)
      // Reset payouts fetch flag to refetch if user changes
      payoutsFetchedRef.current = false
    }
    setPaymentsPage(newPage) // Use paymentsPage for server-side pagination
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
                          color={
                            // Payout states
                            transaction.payoutId 
                              ? (transaction.state === 'COMPLETED' ? 'success' : 
                                 transaction.state === 'PROCESSING' || transaction.state === 'CONFIRMING' ? 'warning' : 'default')
                              // Payment/refund states
                              : (transaction.state === 'CAPTURED' || transaction.state === 'REFUNDED' ? 'success' : 
                                 transaction.state === 'REFUNDING' ? 'warning' : 'default')
                          }
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
                  onChange={handleTransactionsPageChange}
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

    </Container>
  )
}

export default SellerLanding

