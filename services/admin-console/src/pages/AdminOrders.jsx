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
  List,
  ListItem,
  ListItemText,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import SearchIcon from '@mui/icons-material/Search'
import CloseIcon from '@mui/icons-material/Close'
import ReceiptIcon from '@mui/icons-material/Receipt'
import ShoppingBagIcon from '@mui/icons-material/ShoppingBag'
import { getOrders, getOrder, createFullRefund, createPartialRefund, getProduct } from '../api/adminApi'
import ConfirmationModal from '../components/ConfirmationModal'

function AdminOrders() {
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [buyerFilter, setBuyerFilter] = useState('')
  const [debouncedBuyerFilter, setDebouncedBuyerFilter] = useState('')
  const [filterType, setFilterType] = useState('uuid') // 'uuid' or 'email'
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' })
  
  // Order details dialog state
  const [detailsDialogOpen, setDetailsDialogOpen] = useState(false)
  const [viewOrderDetails, setViewOrderDetails] = useState(null)
  const [loadingOrderDetails, setLoadingOrderDetails] = useState(false)
  
  // Refund dialog state
  const [refundDialogOpen, setRefundDialogOpen] = useState(false)
  const [selectedOrder, setSelectedOrder] = useState(null)
  const [orderDetails, setOrderDetails] = useState(null)
  const [selectedItems, setSelectedItems] = useState({}) // { orderItemId: quantity }
  const [refundType, setRefundType] = useState('full') // 'full' or 'partial'
  const [processingRefund, setProcessingRefund] = useState(false)

  const loadOrders = async () => {
    setLoading(true)
    setError('')
    try {
      const filterValue = debouncedBuyerFilter.trim() || null
      const response = await getOrders(page, 20, filterValue, filterType)
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

  // Debounce the buyer filter input (500ms delay)
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedBuyerFilter(buyerFilter)
    }, 500)

    return () => {
      clearTimeout(timer)
    }
  }, [buyerFilter])

  // Load orders when debounced filter, page, or filter type changes
  useEffect(() => {
    loadOrders()
  }, [page, debouncedBuyerFilter, filterType])

  const handleBuyerFilterChange = (e) => {
    setBuyerFilter(e.target.value)
    setPage(0) // Reset to first page when filter changes
    // Note: API call will be triggered after debounce delay via debouncedBuyerFilter
  }

  const handleViewDetails = async (order) => {
    setViewOrderDetails(null)
    setLoadingOrderDetails(true)
    setDetailsDialogOpen(true)
    
    try {
      const details = await getOrder(order.id)
      
      // Fetch product details for each item to get images
      if (details.items && details.items.length > 0) {
        const itemsWithProducts = await Promise.all(
          details.items.map(async (item) => {
            try {
              const product = await getProduct(item.product_id || item.productId)
              return {
                ...item,
                productName: product?.name || `Product ${item.sku || 'Unknown'}`,
                productImage: product?.images?.[0] || null,
              }
            } catch (err) {
              console.error(`Failed to fetch product ${item.product_id || item.productId}:`, err)
              return {
                ...item,
                productName: `Product ${item.sku || 'Unknown'}`,
                productImage: null,
              }
            }
          })
        )
        details.items = itemsWithProducts
      }
      
      setViewOrderDetails(details)
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Failed to load order details: ${err.message}`,
        severity: 'error',
      })
      setDetailsDialogOpen(false)
    } finally {
      setLoadingOrderDetails(false)
    }
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
            <Box sx={{ display: 'flex', gap: 1, mb: 2, alignItems: 'center' }}>
              <Typography variant="body2" color="text.secondary">
                Filter by:
              </Typography>
              <Chip
                label="UUID"
                onClick={() => {
                  setFilterType('uuid')
                  setBuyerFilter('')
                  setPage(0)
                }}
                color={filterType === 'uuid' ? 'primary' : 'default'}
                variant={filterType === 'uuid' ? 'filled' : 'outlined'}
                sx={{ cursor: 'pointer' }}
              />
              <Chip
                label="Email"
                onClick={() => {
                  setFilterType('email')
                  setBuyerFilter('')
                  setPage(0)
                }}
                color={filterType === 'email' ? 'primary' : 'default'}
                variant={filterType === 'email' ? 'filled' : 'outlined'}
                sx={{ cursor: 'pointer' }}
              />
            </Box>
            <TextField
              fullWidth
              placeholder={filterType === 'uuid' ? 'Filter by buyer UUID (starts with)...' : 'Filter by buyer email (starts with)...'}
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
                              {(() => {
                                // Calculate adjusted total (after refunds)
                                const totalRefundedCents = order.items?.reduce((sum, item) => {
                                  const refundedQty = item.refunded_quantity || item.refundedQuantity || 0
                                  const priceCents = item.price_cents || item.priceCents
                                  return sum + (refundedQty * priceCents)
                                }, 0) || 0
                                const originalTotalCents = order.total_cents || order.totalCents || 0
                                const adjustedTotalCents = originalTotalCents - totalRefundedCents
                                const currency = order.currency || 'CAD'
                                
                                return (
                                  <Box>
                                    {totalRefundedCents > 0 ? (
                                      <>
                                        <Typography variant="body2" color="text.secondary" sx={{ textDecoration: 'line-through' }}>
                                          {formatAmount(originalTotalCents, currency)}
                                        </Typography>
                                        <Typography variant="body1" fontWeight="bold" color="success.main">
                                          {formatAmount(adjustedTotalCents, currency)}
                                        </Typography>
                                      </>
                                ) : (
                                  <Typography variant="body1" fontWeight="bold">
                                    {formatAmount(originalTotalCents, currency)}
                                  </Typography>
                                )}
                                  </Box>
                                )
                              })()}
                            </TableCell>
                            <TableCell>
                              {order.items?.reduce((total, item) => total + (item.quantity || 0), 0) || 0} item(s)
                            </TableCell>
                            <TableCell>{formatDate(order.created_at)}</TableCell>
                            <TableCell>
                              <Box sx={{ display: 'flex', gap: 1 }}>
                                <Button
                                  variant="outlined"
                                  size="small"
                                  onClick={() => handleViewDetails(order)}
                                >
                                  View Details
                                </Button>
                                <Button
                                  variant="outlined"
                                  size="small"
                                  color="error"
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    handleRefundClick(order)
                                  }}
                                  disabled={!canRefund}
                                  title={reason || 'Refund order'}
                                >
                                  Refund
                                </Button>
                              </Box>
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
                    Original Total
                  </Typography>
                  <Typography variant="h6">
                    {formatAmount(orderDetails.total_cents || orderDetails.totalCents, orderDetails.currency)}
                  </Typography>
                </Grid>
                {(() => {
                  // Calculate total refunded amount
                  const totalRefundedCents = orderDetails.items?.reduce((sum, item) => {
                    const refundedQty = item.refunded_quantity || item.refundedQuantity || 0
                    const priceCents = item.price_cents || item.priceCents
                    return sum + (refundedQty * priceCents)
                  }, 0) || 0
                  const originalTotalCents = orderDetails.total_cents || orderDetails.totalCents || 0
                  const adjustedTotalCents = originalTotalCents - totalRefundedCents
                  const currency = orderDetails.currency || 'CAD'
                  
                  if (totalRefundedCents > 0) {
                    return (
                      <>
                        <Grid item xs={12} sm={6}>
                          <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                            Refunded Amount
                          </Typography>
                          <Typography variant="h6" color="error.main">
                            -{formatAmount(totalRefundedCents, currency)}
                          </Typography>
                        </Grid>
                        <Grid item xs={12} sm={6}>
                          <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                            Adjusted Total
                          </Typography>
                          <Typography variant="h6" color="success.main" fontWeight="bold">
                            {formatAmount(adjustedTotalCents, currency)}
                          </Typography>
                        </Grid>
                      </>
                    )
                  }
                  return null
                })()}
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
                  {orderDetails.items?.some(item => {
                    const refundedQty = item.refunded_quantity || item.refundedQuantity || 0
                    return (item.quantity - refundedQty) > 0
                  }) ? (
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
                            const refundedQty = item.refunded_quantity || item.refundedQuantity || 0
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
                                <TableCell>{item.seller_id || item.sellerId}</TableCell>
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
                                  {formatAmount((item.price_cents || item.priceCents) * (isSelected ? selectedQty : item.quantity), item.currency)}
                                </TableCell>
                              </TableRow>
                            )
                          })}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  ) : (
                    <Alert severity="warning">
                      All items in this order have already been refunded. No items available for refund.
                    </Alert>
                  )}
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

      {/* Order Details Dialog */}
      <Dialog
        open={detailsDialogOpen}
        onClose={() => {
          setDetailsDialogOpen(false)
          setViewOrderDetails(null)
        }}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <ShoppingBagIcon color="primary" />
            <Typography variant="h6">Order Details</Typography>
          </Box>
          <IconButton
            onClick={() => {
              setDetailsDialogOpen(false)
              setViewOrderDetails(null)
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
          ) : viewOrderDetails ? (
            <Box>
              <Grid container spacing={2} sx={{ mb: 3 }}>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Order ID
                  </Typography>
                  <Typography variant="body1" component="code" sx={{ fontFamily: 'monospace' }}>
                    {viewOrderDetails.id}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Buyer ID
                  </Typography>
                  <Typography variant="body1">
                    {viewOrderDetails.buyer_id || viewOrderDetails.buyerId}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Status
                  </Typography>
                  <Chip
                    label={viewOrderDetails.status}
                    color={getStatusColor(viewOrderDetails.status)}
                    size="small"
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Refund Status
                  </Typography>
                  <Chip
                    label={viewOrderDetails.refund_status || viewOrderDetails.refundStatus || 'NONE'}
                    color={getRefundStatusColor(viewOrderDetails.refund_status || viewOrderDetails.refundStatus)}
                    size="small"
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Original Total
                  </Typography>
                  <Typography variant="h6">
                    {formatAmount(viewOrderDetails.total_cents || viewOrderDetails.totalCents, viewOrderDetails.currency)}
                  </Typography>
                </Grid>
                {(() => {
                  // Calculate total refunded amount
                  const totalRefundedCents = viewOrderDetails.items?.reduce((sum, item) => {
                    const refundedQty = item.refunded_quantity || item.refundedQuantity || 0
                    const priceCents = item.price_cents || item.priceCents
                    return sum + (refundedQty * priceCents)
                  }, 0) || 0
                  const originalTotalCents = viewOrderDetails.total_cents || viewOrderDetails.totalCents || 0
                  const adjustedTotalCents = originalTotalCents - totalRefundedCents
                  const currency = viewOrderDetails.currency || 'CAD'
                  
                  if (totalRefundedCents > 0) {
                    return (
                      <>
                        <Grid item xs={12} sm={6}>
                          <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                            Refunded Amount
                          </Typography>
                          <Typography variant="h6" color="error.main">
                            -{formatAmount(totalRefundedCents, currency)}
                          </Typography>
                        </Grid>
                        <Grid item xs={12} sm={6}>
                          <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                            Adjusted Total
                          </Typography>
                          <Typography variant="h6" color="success.main" fontWeight="bold">
                            {formatAmount(adjustedTotalCents, currency)}
                          </Typography>
                        </Grid>
                      </>
                    )
                  }
                  return null
                })()}
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Payment ID
                  </Typography>
                  <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                    {viewOrderDetails.payment_id || viewOrderDetails.paymentId || 'N/A'}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Created
                  </Typography>
                  <Typography variant="body2">
                    {formatDate(viewOrderDetails.created_at || viewOrderDetails.createdAt)}
                  </Typography>
                </Grid>
                {(viewOrderDetails.confirmed_at || viewOrderDetails.confirmedAt) && (
                  <Grid item xs={12} sm={6}>
                    <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                      Confirmed
                    </Typography>
                    <Typography variant="body2">
                      {formatDate(viewOrderDetails.confirmed_at || viewOrderDetails.confirmedAt)}
                    </Typography>
                  </Grid>
                )}
              </Grid>

              {(() => {
                // Calculate totals for summary
                const totalRefundedCents = viewOrderDetails.items?.reduce((sum, item) => {
                  const refundedQty = item.refunded_quantity || item.refundedQuantity || 0
                  const priceCents = item.price_cents || item.priceCents
                  return sum + (refundedQty * priceCents)
                }, 0) || 0
                const originalTotalCents = viewOrderDetails.total_cents || viewOrderDetails.totalCents || 0
                const adjustedTotalCents = originalTotalCents - totalRefundedCents
                const currency = viewOrderDetails.currency || 'CAD'
                
                if (totalRefundedCents > 0) {
                  return (
                    <>
                      <Divider sx={{ my: 3 }} />
                      <Box sx={{ 
                        bgcolor: 'grey.50', 
                        p: 2, 
                        borderRadius: 2, 
                        border: '1px solid',
                        borderColor: 'divider',
                        mb: 3
                      }}>
                        <Typography variant="h6" gutterBottom>
                          Order Summary
                        </Typography>
                        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                          <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                            <Typography variant="body2" color="text.secondary">
                              Original Order Total:
                            </Typography>
                            <Typography variant="body2" fontWeight="medium">
                              {formatAmount(originalTotalCents, currency)}
                            </Typography>
                          </Box>
                          <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                            <Typography variant="body2" color="text.secondary">
                              Total Refunded:
                            </Typography>
                            <Typography variant="body2" fontWeight="medium" color="error.main">
                              -{formatAmount(totalRefundedCents, currency)}
                            </Typography>
                          </Box>
                          <Divider />
                          <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                            <Typography variant="body1" fontWeight="bold">
                              Final Amount:
                            </Typography>
                            <Typography variant="body1" fontWeight="bold" color="success.main">
                              {formatAmount(adjustedTotalCents, currency)}
                            </Typography>
                          </Box>
                        </Box>
                      </Box>
                    </>
                  )
                }
                return null
              })()}

              <Divider sx={{ my: 3 }} />

              <Typography variant="h6" gutterBottom>
                Order Items ({viewOrderDetails.items?.length || 0})
              </Typography>
              <List>
                {viewOrderDetails.items?.map((item, index) => {
                  const refundedQty = item.refunded_quantity || item.refundedQuantity || 0
                  const availableQty = item.quantity - refundedQty
                  const priceCents = item.price_cents || item.priceCents
                  const currency = item.currency
                  const productName = item.productName || `Product ${item.sku || 'Unknown'}`
                  const productImage = item.productImage
                  
                  return (
                    <Box key={item.id}>
                      <ListItem>
                        {productImage && (
                          <Box
                            component="img"
                            src={productImage}
                            alt={productName}
                            sx={{
                              width: 60,
                              height: 60,
                              objectFit: 'cover',
                              borderRadius: 1,
                              mr: 2,
                            }}
                            onError={(e) => {
                              e.target.style.display = 'none'
                            }}
                          />
                        )}
                        <ListItemText
                          primary={
                            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
                              <Box>
                                <Typography variant="body1" fontWeight="medium">
                                  {productName}
                                </Typography>
                                <Typography variant="caption" color="text.secondary">
                                  SKU: {item.sku}
                                </Typography>
                              </Box>
                              <Box sx={{ textAlign: 'right' }}>
                                {refundedQty > 0 ? (
                                  <>
                                    <Typography variant="body2" color="text.secondary" sx={{ textDecoration: 'line-through' }}>
                                      {formatAmount(priceCents * item.quantity, currency)}
                                    </Typography>
                                    <Typography variant="body1" fontWeight="bold" color="error.main">
                                      -{formatAmount(priceCents * refundedQty, currency)} refunded
                                    </Typography>
                                    <Typography variant="body1" fontWeight="bold" color="success.main">
                                      {formatAmount(priceCents * availableQty, currency)} remaining
                                    </Typography>
                                  </>
                                ) : (
                                  <Typography variant="body1" fontWeight="bold">
                                    {formatAmount(priceCents * item.quantity, currency)}
                                  </Typography>
                                )}
                              </Box>
                            </Box>
                          }
                          secondary={
                            <Box sx={{ mt: 1 }}>
                              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                                <Typography variant="body2" color="text.secondary">
                                  Quantity: {item.quantity}
                                </Typography>
                                {refundedQty > 0 && (
                                  <>
                                    <Chip
                                      label={`${refundedQty} refunded`}
                                      color="error"
                                      size="small"
                                      variant="outlined"
                                    />
                                    <Chip
                                      label={`${availableQty} remaining`}
                                      color={availableQty > 0 ? 'success' : 'default'}
                                      size="small"
                                      variant="outlined"
                                    />
                                  </>
                                )}
                              </Box>
                              <Typography variant="caption" color="text.secondary">
                                Price: {formatAmount(priceCents, currency)} each
                              </Typography>
                              <Typography variant="caption" color="text.secondary" display="block">
                                Seller: {item.seller_id || item.sellerId}
                              </Typography>
                            </Box>
                          }
                        />
                      </ListItem>
                      {index < viewOrderDetails.items.length - 1 && <Divider />}
                    </Box>
                  )
                })}
              </List>

              {viewOrderDetails.shipments && viewOrderDetails.shipments.length > 0 && (
                <>
                  <Divider sx={{ my: 3 }} />
                  <Typography variant="h6" gutterBottom>
                    Shipments ({viewOrderDetails.shipments.length})
                  </Typography>
                  <List>
                    {viewOrderDetails.shipments.map((shipment, index) => {
                      const shipmentItems = viewOrderDetails.items.filter(item => 
                        (item.shipment_id || item.shipmentId) === (shipment.id || shipment.id)
                      )
                      return (
                        <Box key={shipment.id || index}>
                          <ListItem>
                            <ListItemText
                              primary={
                                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                  <Typography variant="body1" fontWeight="medium">
                                    Seller: {shipment.seller_id || shipment.sellerId} ({shipmentItems.length} items)
                                  </Typography>
                                  <Chip
                                    label={shipment.status}
                                    color={shipment.status === 'DELIVERED' ? 'success' : shipment.status === 'SHIPPED' ? 'info' : 'default'}
                                    size="small"
                                  />
                                </Box>
                              }
                              secondary={
                                <Box sx={{ mt: 1 }}>
                                  {shipment.tracking_number || shipment.trackingNumber ? (
                                    <Typography variant="body2" color="text.secondary">
                                      Tracking: {shipment.tracking_number || shipment.trackingNumber}
                                    </Typography>
                                  ) : null}
                                  {shipment.carrier && (
                                    <Typography variant="body2" color="text.secondary">
                                      Carrier: {shipment.carrier}
                                    </Typography>
                                  )}
                                  {shipment.shipped_at || shipment.shippedAt ? (
                                    <Typography variant="caption" color="text.secondary" display="block">
                                      Shipped: {formatDate(shipment.shipped_at || shipment.shippedAt)}
                                    </Typography>
                                  ) : null}
                                  {shipment.delivered_at || shipment.deliveredAt ? (
                                    <Typography variant="caption" color="text.secondary" display="block">
                                      Delivered: {formatDate(shipment.delivered_at || shipment.deliveredAt)}
                                    </Typography>
                                  ) : null}
                                </Box>
                              }
                            />
                          </ListItem>
                          {index < viewOrderDetails.shipments.length - 1 && <Divider />}
                        </Box>
                      )
                    })}
                  </List>
                </>
              )}
            </Box>
          ) : (
            <Alert severity="error">Failed to load order details</Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button
            onClick={() => {
              setDetailsDialogOpen(false)
              setViewOrderDetails(null)
            }}
          >
            Close
          </Button>
          {viewOrderDetails && canRefundOrder({ 
            status: viewOrderDetails.status, 
            payment_id: viewOrderDetails.payment_id || viewOrderDetails.paymentId,
            refund_status: viewOrderDetails.refund_status || viewOrderDetails.refundStatus
          }).canRefund && (
            <Button
              onClick={() => {
                setDetailsDialogOpen(false)
                handleRefundClick({ id: viewOrderDetails.id })
              }}
              variant="contained"
              color="error"
            >
              Refund Order
            </Button>
          )}
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
