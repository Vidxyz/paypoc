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
  Checkbox,
  FormControlLabel,
  IconButton,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import SearchIcon from '@mui/icons-material/Search'
import CloseIcon from '@mui/icons-material/Close'
import ReceiptIcon from '@mui/icons-material/Receipt'
import { getOrders, getOrder, createFullRefund, createPartialRefund } from '../api/adminApi'
import ConfirmationModal from '../components/ConfirmationModal'

function AdminOrders() {
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [buyerFilter, setBuyerFilter] = useState('')
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' })
  
  // Refund dialog state
  const [refundDialogOpen, setRefundDialogOpen] = useState(false)
  const [selectedOrder, setSelectedOrder] = useState(null)
  const [orderDetails, setOrderDetails] = useState(null)
  const [loadingOrderDetails, setLoadingOrderDetails] = useState(false)
  const [selectedItems, setSelectedItems] = useState({}) // { orderItemId: quantity }
  const [refundType, setRefundType] = useState('full') // 'full' or 'partial'
  const [processingRefund, setProcessingRefund] = useState(false)

  const loadOrders = async () => {
    setLoading(true)
    setError('')
    try {
      const buyerId = buyerFilter.trim() || null
      const response = await getOrders(page, 20, buyerId)
      if (response.error) {
        setError(response.error)
      } else {
        setOrders(response.orders || [])
        setTotalPages(response.total_pages || 0)
        setTotal(response.total || 0)
      }
    } catch (err) {
      setError(`Failed to load orders: ${err.message}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadOrders()
  }, [page, buyerFilter])

  const handleBuyerFilterChange = (e) => {
    setBuyerFilter(e.target.value)
    setPage(0) // Reset to first page when filter changes
  }

  const handleRefundClick = async (order) => {
    setSelectedOrder(order)
    setLoadingOrderDetails(true)
    setRefundDialogOpen(true)
    setSelectedItems({})
    setRefundType('full')
    
    try {
      const details = await getOrder(order.id)
      setOrderDetails(details)
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Failed to load order details: ${err.message}`,
        severity: 'error',
      })
      setRefundDialogOpen(false)
    } finally {
      setLoadingOrderDetails(false)
    }
  }

  const handleItemSelectionChange = (itemId, maxQuantity) => {
    setSelectedItems((prev) => {
      const newSelection = { ...prev }
      if (newSelection[itemId]) {
        delete newSelection[itemId]
      } else {
        newSelection[itemId] = maxQuantity
      }
      return newSelection
    })
  }

  const handleQuantityChange = (itemId, newQuantity, maxQuantity) => {
    if (newQuantity < 1) {
      return
    }
    if (newQuantity > maxQuantity) {
      newQuantity = maxQuantity
    }
    setSelectedItems((prev) => ({
      ...prev,
      [itemId]: newQuantity,
    }))
  }

  const handleRefundConfirm = async () => {
    if (!selectedOrder || !orderDetails) return

    setProcessingRefund(true)

    try {
      if (refundType === 'full') {
        // Full refund - call order service endpoint
        await createFullRefund(selectedOrder.id)
        
        setSnackbar({
          open: true,
          message: 'Full refund created successfully',
          severity: 'success',
        })
        
        setRefundDialogOpen(false)
        setSelectedOrder(null)
        setOrderDetails(null)
        setSelectedItems({})
        
        // Reload orders after a short delay
        setTimeout(() => {
          loadOrders()
        }, 1000)
      } else {
        // Partial refund
        const orderItemsToRefund = Object.entries(selectedItems).map(([itemId, quantity]) => ({
          orderItemId: itemId,
          quantity: parseInt(quantity),
        }))

        if (orderItemsToRefund.length === 0) {
          throw new Error('Please select at least one item to refund')
        }

        await createPartialRefund(selectedOrder.id, orderItemsToRefund)
        
        setSnackbar({
          open: true,
          message: 'Partial refund created successfully',
          severity: 'success',
        })
        
        setRefundDialogOpen(false)
        setSelectedOrder(null)
        setOrderDetails(null)
        setSelectedItems({})
        
        // Reload orders after a short delay
        setTimeout(() => {
          loadOrders()
        }, 1000)
      }
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Refund failed: ${err.response?.data?.error || err.message}`,
        severity: 'error',
      })
    } finally {
      setProcessingRefund(false)
    }
  }

  const canRefundOrder = (order) => {
    // Only confirmed orders with payment can be refunded
    if (order.status !== 'CONFIRMED') {
      return { canRefund: false, reason: 'Only confirmed orders can be refunded' }
    }
    
    if (!order.payment_id) {
      return { canRefund: false, reason: 'Order has no payment ID' }
    }
    
    // Check if already fully refunded
    if (order.refund_status === 'FULL') {
      return { canRefund: false, reason: 'Order is already fully refunded' }
    }
    
    return { canRefund: true, reason: null }
  }

  const getStatusColor = (status) => {
    switch (status) {
      case 'CONFIRMED':
        return 'success'
      case 'PENDING':
        return 'warning'
      case 'CANCELLED':
        return 'error'
      default:
        return 'default'
    }
  }

  const getRefundStatusColor = (status) => {
    switch (status) {
      case 'FULL':
        return 'error'
      case 'PARTIAL':
        return 'warning'
      case 'NONE':
        return 'default'
      default:
        return 'default'
    }
  }

  const formatAmount = (cents, currency = 'CAD') => {
    return new Intl.NumberFormat('en-CA', {
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
    <Container maxWidth="xl" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
            <Typography
              variant="h4"
              component="h1"
              sx={{
                fontWeight: 700,
                background: 'linear-gradient(135deg, #1976d2 0%, #1565c0 100%)',
                backgroundClip: 'text',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
              }}
            >
              Orders
            </Typography>
            <Button
              variant="outlined"
              startIcon={<RefreshIcon />}
              onClick={loadOrders}
              disabled={loading}
            >
              Refresh
            </Button>
          </Box>

          <Box sx={{ mb: 3 }}>
            <TextField
              fullWidth
              placeholder="Filter by buyer ID..."
              value={buyerFilter}
              onChange={handleBuyerFilterChange}
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
              <Box sx={{ mb: 2 }}>
                <Typography variant="body2" color="text.secondary">
                  Total: {total} orders
                </Typography>
              </Box>
              
              <TableContainer component={Paper}>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Order ID</TableCell>
                      <TableCell>Buyer ID</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Refund Status</TableCell>
                      <TableCell>Total</TableCell>
                      <TableCell>Items</TableCell>
                      <TableCell>Created</TableCell>
                      <TableCell>Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {orders.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={8} align="center">
                          <Typography variant="body2" color="text.secondary">
                            No orders found
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ) : (
                      orders.map((order) => {
                        const { canRefund, reason } = canRefundOrder(order)
                        return (
                          <TableRow key={order.id}>
                            <TableCell>
                              <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                                {order.id.substring(0, 8)}...
                              </Typography>
                            </TableCell>
                            <TableCell>{order.buyer_id}</TableCell>
                            <TableCell>
                              <Chip
                                label={order.status}
                                color={getStatusColor(order.status)}
                                size="small"
                              />
                            </TableCell>
                            <TableCell>
                              <Chip
                                label={order.refund_status || 'NONE'}
                                color={getRefundStatusColor(order.refund_status)}
                                size="small"
                              />
                            </TableCell>
                            <TableCell>
                              {formatAmount(order.total_cents, order.currency)}
                            </TableCell>
                            <TableCell>
                              {order.items?.length || 0} item(s)
                            </TableCell>
                            <TableCell>{formatDate(order.created_at)}</TableCell>
                            <TableCell>
                              <Button
                                variant="outlined"
                                size="small"
                                color="error"
                                onClick={() => handleRefundClick(order)}
                                disabled={!canRefund}
                                title={reason || 'Refund order'}
                              >
                                Refund
                              </Button>
                            </TableCell>
                          </TableRow>
                        )
                      })
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

      {/* Refund Dialog */}
      <Dialog
        open={refundDialogOpen}
        onClose={() => {
          setRefundDialogOpen(false)
          setSelectedOrder(null)
          setOrderDetails(null)
          setSelectedItems({})
        }}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <ReceiptIcon color="error" />
            <Typography variant="h6">Refund Order</Typography>
          </Box>
          <IconButton
            onClick={() => {
              setRefundDialogOpen(false)
              setSelectedOrder(null)
              setOrderDetails(null)
              setSelectedItems({})
            }}
            size="small"
          >
            <CloseIcon />
          </IconButton>
        </DialogTitle>
        <DialogContent>
          {loadingOrderDetails ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
              <CircularProgress />
            </Box>
          ) : orderDetails ? (
            <Box>
              <Grid container spacing={2} sx={{ mb: 3 }}>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Order ID
                  </Typography>
                  <Typography variant="body1" sx={{ fontFamily: 'monospace' }}>
                    {orderDetails.id}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Total Amount
                  </Typography>
                  <Typography variant="h6">
                    {formatAmount(orderDetails.total_cents, orderDetails.currency)}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Status
                  </Typography>
                  <Chip
                    label={orderDetails.status}
                    color={getStatusColor(orderDetails.status)}
                    size="small"
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Refund Status
                  </Typography>
                  <Chip
                    label={orderDetails.refund_status || 'NONE'}
                    color={getRefundStatusColor(orderDetails.refund_status)}
                    size="small"
                  />
                </Grid>
              </Grid>

              <Divider sx={{ my: 2 }} />

              <Box sx={{ mb: 2 }}>
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={refundType === 'full'}
                      onChange={(e) => {
                        setRefundType(e.target.checked ? 'full' : 'partial')
                        if (e.target.checked) {
                          // Clear selections when switching to full refund
                          setSelectedItems({})
                        }
                      }}
                    />
                  }
                  label="Full Refund (all remaining items)"
                />
                {refundType === 'full' && (
                  <Alert severity="info" sx={{ mt: 1 }}>
                    All remaining items in this order will be refunded.
                  </Alert>
                )}
              </Box>

              {refundType === 'partial' && (
                <Box>
                  <Typography variant="subtitle1" gutterBottom>
                    Select Items to Refund
                  </Typography>
                  <TableContainer>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell padding="checkbox">Select</TableCell>
                          <TableCell>SKU</TableCell>
                          <TableCell>Seller</TableCell>
                          <TableCell>Quantity</TableCell>
                          <TableCell>Available</TableCell>
                          <TableCell>Price</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {orderDetails.items?.map((item) => {
                          const refundedQty = item.refunded_quantity || 0
                          const availableQty = item.quantity - refundedQty
                          const isSelected = !!selectedItems[item.id]
                          const selectedQty = selectedItems[item.id] || 0

                          return (
                            <TableRow key={item.id}>
                              <TableCell padding="checkbox">
                                <Checkbox
                                  checked={isSelected}
                                  onChange={() => handleItemSelectionChange(item.id, availableQty)}
                                  disabled={availableQty === 0}
                                />
                              </TableCell>
                              <TableCell>{item.sku}</TableCell>
                              <TableCell>{item.seller_id}</TableCell>
                              <TableCell>
                                {isSelected ? (
                                  <TextField
                                    type="number"
                                    size="small"
                                    value={selectedQty}
                                    onChange={(e) =>
                                      handleQuantityChange(
                                        item.id,
                                        parseInt(e.target.value) || 0,
                                        availableQty
                                      )
                                    }
                                    inputProps={{
                                      min: 1,
                                      max: availableQty,
                                    }}
                                    sx={{ width: 80 }}
                                  />
                                ) : (
                                  item.quantity
                                )}
                              </TableCell>
                              <TableCell>
                                {availableQty > 0 ? (
                                  <Chip label={availableQty} color="success" size="small" />
                                ) : (
                                  <Chip label="0" color="error" size="small" />
                                )}
                              </TableCell>
                              <TableCell>
                                {formatAmount(item.price_cents * (isSelected ? selectedQty : item.quantity), item.currency)}
                              </TableCell>
                            </TableRow>
                          )
                        })}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Box>
              )}
            </Box>
          ) : (
            <Alert severity="error">Failed to load order details</Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button
            onClick={() => {
              setRefundDialogOpen(false)
              setSelectedOrder(null)
              setOrderDetails(null)
              setSelectedItems({})
            }}
            disabled={processingRefund}
          >
            Cancel
          </Button>
          <Button
            onClick={handleRefundConfirm}
            variant="contained"
            color="error"
            disabled={
              processingRefund ||
              (refundType === 'partial' && Object.keys(selectedItems).length === 0) ||
              (refundType === 'full' && orderDetails?.refund_status === 'FULL')
            }
          >
            {processingRefund ? (
              <>
                <CircularProgress size={16} sx={{ mr: 1 }} />
                Processing...
              </>
            ) : refundType === 'full' ? (
              'Confirm Full Refund'
            ) : (
              'Confirm Partial Refund'
            )}
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

export default AdminOrders
