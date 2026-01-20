import { useState, useEffect } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Button,
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
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Divider,
  Grid,
  List,
  ListItem,
  ListItemText,
  IconButton,
  Pagination,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import ErrorIcon from '@mui/icons-material/Error'
import WarningIcon from '@mui/icons-material/Warning'
import InfoIcon from '@mui/icons-material/Info'
import ShoppingBagIcon from '@mui/icons-material/ShoppingBag'
import CloseIcon from '@mui/icons-material/Close'
import { createOrderApiClient } from '../api/orderApi'
import { createCatalogApiClient } from '../api/catalogApi'
import { useAuth0 } from '../auth/Auth0Provider'

function SellerOrders({ userEmail }) {
  const { getAccessToken } = useAuth0()
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedOrder, setSelectedOrder] = useState(null)
  const [orderDetailsOpen, setOrderDetailsOpen] = useState(false)
  const [loadingOrderDetails, setLoadingOrderDetails] = useState(false)
  const [orderDetails, setOrderDetails] = useState(null)
  const [page, setPage] = useState(0)
  const [pageSize] = useState(20)
  const [totalPages, setTotalPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [orderApiClient, setOrderApiClient] = useState(null)
  const [catalogApiClient, setCatalogApiClient] = useState(null)

  useEffect(() => {
    const orderClient = createOrderApiClient(getAccessToken)
    const catalogClient = createCatalogApiClient(getAccessToken)
    setOrderApiClient(() => orderClient)
    setCatalogApiClient(() => catalogClient)
    
    return () => {
      if (orderClient.cleanup) orderClient.cleanup()
      if (catalogClient.cleanup) catalogClient.cleanup()
    }
  }, [getAccessToken])

  useEffect(() => {
    if (orderApiClient) {
      loadOrders()
    }
  }, [page, orderApiClient])

  const loadOrders = async () => {
    if (!orderApiClient) return
    
    setLoading(true)
    setError('')
    
    try {
      const response = await orderApiClient.getOrders({
        page,
        pageSize,
      })
      
      if (response.error) {
        throw new Error(response.error)
      }
      
      setOrders(response.orders || [])
      setTotalPages(response.total_pages || response.totalPages || 0)
      setTotal(response.total || 0)
    } catch (err) {
      if (err.response?.status === 401) {
        setError('Unauthorized. Please log in again.')
      } else {
        setError(err.message || 'Failed to load orders')
      }
    } finally {
      setLoading(false)
    }
  }

  const handleOrderClick = async (order) => {
    if (!orderApiClient || !catalogApiClient) return
    
    setSelectedOrder(order)
    setOrderDetailsOpen(true)
    setLoadingOrderDetails(true)
    setOrderDetails(null)

    try {
      const details = await orderApiClient.getOrder(order.id)
      
      // Fetch product details for each item (seller only sees their items)
      if (details.items && details.items.length > 0) {
        const itemsWithProducts = await Promise.all(
          details.items.map(async (item) => {
            try {
              const product = await catalogApiClient.getProduct(item.product_id || item.productId)
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
      
      setOrderDetails(details)
    } catch (err) {
      setError(err.message || 'Failed to load order details')
    } finally {
      setLoadingOrderDetails(false)
    }
  }

  const getStatusChip = (status) => {
    const chips = {
      PENDING: { label: status, color: 'warning', icon: <WarningIcon /> },
      CONFIRMED: { label: status, color: 'success', icon: <CheckCircleIcon /> },
      CANCELLED: { label: status, color: 'error', icon: <ErrorIcon /> },
      PROCESSING: { label: status, color: 'info', icon: <InfoIcon /> },
      SHIPPED: { label: status, color: 'info', icon: <InfoIcon /> },
      DELIVERED: { label: status, color: 'success', icon: <CheckCircleIcon /> },
    }
    const chip = chips[status] || { label: status, color: 'default', icon: <InfoIcon /> }
    return (
      <Chip
        icon={chip.icon}
        label={chip.label}
        color={chip.color}
        size="small"
      />
    )
  }

  const getRefundStatusChip = (refundStatus) => {
    if (!refundStatus || refundStatus === 'NONE') {
      return null
    }

    const chips = {
      PARTIAL: { label: 'Partially Refunded', color: 'warning' },
      FULL: { label: 'Fully Refunded', color: 'secondary' },
    }
    const chip = chips[refundStatus] || { label: refundStatus, color: 'default' }
    return (
      <Chip
        label={chip.label}
        color={chip.color}
        size="small"
        sx={{ ml: 1 }}
      />
    )
  }

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A'
    try {
      const date = typeof dateString === 'string' ? new Date(dateString) : dateString
      if (isNaN(date.getTime())) {
        return 'N/A'
      }
      return date.toLocaleString()
    } catch {
      return 'N/A'
    }
  }

  const formatAmount = (cents, currency = 'CAD') => {
    if (cents === null || cents === undefined || isNaN(cents)) {
      return 'N/A'
    }
    return new Intl.NumberFormat('en-CA', {
      style: 'currency',
      currency: currency || 'CAD',
    }).format(cents / 100)
  }

  const handlePageChange = (event, value) => {
    setPage(value - 1) // MUI Pagination is 1-indexed, API is 0-indexed
  }

  // Calculate total revenue for seller's items in an order
  const calculateSellerRevenue = (items) => {
    if (!items || items.length === 0) return 0
    return items.reduce((total, item) => {
      const priceCents = item.price_cents || item.priceCents || 0
      const quantity = item.quantity || 0
      return total + (priceCents * quantity)
    }, 0)
  }

  if (loading && orders.length === 0) {
    return (
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Card>
          <CardContent sx={{ textAlign: 'center', py: 8 }}>
            <CircularProgress />
            <Typography variant="body1" color="text.secondary" sx={{ mt: 2 }}>
              Loading orders...
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
                My Orders
              </Typography>
              <Typography variant="body1" color="text.secondary">
                Orders containing your products
              </Typography>
            </Box>
            <Button
              onClick={loadOrders}
              variant="outlined"
              startIcon={<RefreshIcon />}
            >
              Refresh
            </Button>
          </Box>

          {error && (
            <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
              {error}
            </Alert>
          )}

          {orders.length === 0 ? (
            <Box sx={{ textAlign: 'center', py: 8 }}>
              <ShoppingBagIcon sx={{ fontSize: 64, color: 'text.secondary', mb: 2 }} />
              <Typography variant="h6" color="text.secondary" gutterBottom>
                No orders found
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Orders containing your products will appear here.
              </Typography>
            </Box>
          ) : (
            <>
              <TableContainer component={Paper} variant="outlined">
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell><strong>Order ID</strong></TableCell>
                      <TableCell><strong>Date</strong></TableCell>
                      <TableCell><strong>Status</strong></TableCell>
                      <TableCell><strong>Your Revenue</strong></TableCell>
                      <TableCell><strong>Items</strong></TableCell>
                      <TableCell><strong>Payment</strong></TableCell>
                      <TableCell><strong>Refund</strong></TableCell>
                      <TableCell><strong>Actions</strong></TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {orders.map((order) => {
                      const sellerRevenue = calculateSellerRevenue(order.items)
                      const itemCount = order.items?.length || 0
                      
                      return (
                        <TableRow 
                          key={order.id} 
                          hover
                          sx={{ cursor: 'pointer' }}
                          onClick={() => handleOrderClick(order)}
                        >
                          <TableCell>
                            <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                              {order.id?.substring(0, 8)}...
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">
                              {formatDate(order.created_at || order.createdAt)}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            {getStatusChip(order.status)}
                          </TableCell>
                          <TableCell>
                            <Typography variant="body1" fontWeight="bold">
                              {formatAmount(sellerRevenue, order.currency)}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">
                              {itemCount} item{itemCount !== 1 ? 's' : ''}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            {order.payment_id || order.paymentId ? (
                              <Chip
                                label="Paid"
                                color="success"
                                size="small"
                              />
                            ) : (
                              <Chip
                                label="Pending"
                                color="warning"
                                size="small"
                              />
                            )}
                          </TableCell>
                          <TableCell>
                            {getRefundStatusChip(order.refund_status || order.refundStatus)}
                          </TableCell>
                          <TableCell onClick={(e) => e.stopPropagation()}>
                            <Button
                              size="small"
                              variant="outlined"
                              onClick={() => handleOrderClick(order)}
                            >
                              View Details
                            </Button>
                          </TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              </TableContainer>

              {totalPages > 1 && (
                <Box sx={{ display: 'flex', justifyContent: 'center', mt: 3 }}>
                  <Pagination
                    count={totalPages}
                    page={page + 1}
                    onChange={handlePageChange}
                    color="primary"
                  />
                </Box>
              )}
            </>
          )}
        </CardContent>
      </Card>

      {/* Order Details Dialog */}
      <Dialog
        open={orderDetailsOpen}
        onClose={() => {
          setOrderDetailsOpen(false)
          setSelectedOrder(null)
          setOrderDetails(null)
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
              setOrderDetailsOpen(false)
              setSelectedOrder(null)
              setOrderDetails(null)
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
                  <Typography variant="body1" component="code" sx={{ fontFamily: 'monospace' }}>
                    {orderDetails.id}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Status
                  </Typography>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    {getStatusChip(orderDetails.status)}
                    {getRefundStatusChip(orderDetails.refund_status || orderDetails.refundStatus)}
                  </Box>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Your Revenue
                  </Typography>
                  <Typography variant="h6">
                    {formatAmount(calculateSellerRevenue(orderDetails.items), orderDetails.currency)}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Payment Status
                  </Typography>
                  {orderDetails.payment_id || orderDetails.paymentId ? (
                    <Box>
                      <Chip
                        label="Paid"
                        color="success"
                        size="small"
                        sx={{ mb: 0.5 }}
                      />
                      <Typography variant="caption" color="text.secondary" display="block">
                        Payment ID: {(orderDetails.payment_id || orderDetails.paymentId).substring(0, 8)}...
                      </Typography>
                    </Box>
                  ) : (
                    <Chip
                      label="Pending Payment"
                      color="warning"
                      size="small"
                    />
                  )}
                </Grid>
                <Grid item xs={12} sm={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Created
                  </Typography>
                  <Typography variant="body2">
                    {formatDate(orderDetails.created_at || orderDetails.createdAt)}
                  </Typography>
                </Grid>
                {(orderDetails.confirmed_at || orderDetails.confirmedAt) && (
                  <Grid item xs={12} sm={6}>
                    <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                      Confirmed
                    </Typography>
                    <Typography variant="body2">
                      {formatDate(orderDetails.confirmed_at || orderDetails.confirmedAt)}
                    </Typography>
                  </Grid>
                )}
              </Grid>

              <Divider sx={{ my: 3 }} />

              <Typography variant="h6" gutterBottom>
                Your Items ({orderDetails.items?.length || 0})
              </Typography>
              <List>
                {orderDetails.items?.map((item, index) => {
                  const productId = item.product_id || item.productId
                  const productName = item.productName || `Product ${item.sku || 'Unknown'}`
                  const productImage = item.productImage
                  const priceCents = item.price_cents || item.priceCents
                  const quantity = item.quantity
                  const refundedQuantity = item.refunded_quantity || item.refundedQuantity || 0
                  const currency = item.currency
                  
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
                                {refundedQuantity > 0 ? (
                                  <>
                                    <Typography variant="body2" color="text.secondary" sx={{ textDecoration: 'line-through' }}>
                                      {formatAmount(priceCents * quantity, currency)}
                                    </Typography>
                                    <Typography variant="body1" fontWeight="bold" color="error.main">
                                      -{formatAmount(priceCents * refundedQuantity, currency)} refunded
                                    </Typography>
                                    <Typography variant="body1" fontWeight="bold" color="success.main">
                                      {formatAmount(priceCents * (quantity - refundedQuantity), currency)} remaining
                                    </Typography>
                                  </>
                                ) : (
                                  <Typography variant="body1" fontWeight="bold">
                                    {formatAmount(priceCents * quantity, currency)}
                                  </Typography>
                                )}
                              </Box>
                            </Box>
                          }
                          secondary={
                            <Box sx={{ mt: 1 }}>
                              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                                <Typography variant="body2" color="text.secondary">
                                  Quantity: {quantity}
                                </Typography>
                                {refundedQuantity > 0 && (
                                  <>
                                    <Chip
                                      label={`${refundedQuantity} refunded`}
                                      color="error"
                                      size="small"
                                      variant="outlined"
                                    />
                                    <Chip
                                      label={`${quantity - refundedQuantity} remaining`}
                                      color="success"
                                      size="small"
                                      variant="outlined"
                                    />
                                  </>
                                )}
                              </Box>
                              <Typography variant="caption" color="text.secondary">
                                Price: {formatAmount(priceCents, currency)} each
                              </Typography>
                            </Box>
                          }
                        />
                      </ListItem>
                      {index < orderDetails.items.length - 1 && <Divider />}
                    </Box>
                  )
                })}
              </List>

              {orderDetails.shipments && orderDetails.shipments.length > 0 && (
                <>
                  <Divider sx={{ my: 3 }} />
                  <Typography variant="h6" gutterBottom>
                    Shipment Status
                  </Typography>
                  <List>
                    {orderDetails.shipments.map((shipment, index) => {
                      const shipmentStatus = shipment.status
                      const trackingNumber = shipment.tracking_number || shipment.trackingNumber
                      const carrier = shipment.carrier
                      const shippedAt = shipment.shipped_at || shipment.shippedAt
                      const deliveredAt = shipment.delivered_at || shipment.deliveredAt
                      
                      return (
                        <Box key={shipment.id || index}>
                          <ListItem>
                            <ListItemText
                              primary={
                                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
                                  <Typography variant="body1" fontWeight="medium">
                                    Your Shipment
                                  </Typography>
                                  <Chip
                                    label={shipmentStatus}
                                    color={shipmentStatus === 'DELIVERED' ? 'success' : shipmentStatus === 'SHIPPED' ? 'info' : 'default'}
                                    size="small"
                                  />
                                </Box>
                              }
                              secondary={
                                <Box sx={{ mt: 1 }}>
                                  {trackingNumber && (
                                    <Typography variant="body2" color="text.secondary">
                                      Tracking: {trackingNumber}
                                    </Typography>
                                  )}
                                  {carrier && (
                                    <Typography variant="body2" color="text.secondary">
                                      Carrier: {carrier}
                                    </Typography>
                                  )}
                                  {shippedAt && (
                                    <Typography variant="caption" color="text.secondary" display="block">
                                      Shipped: {formatDate(shippedAt)}
                                    </Typography>
                                  )}
                                  {deliveredAt && (
                                    <Typography variant="caption" color="text.secondary" display="block">
                                      Delivered: {formatDate(deliveredAt)}
                                    </Typography>
                                  )}
                                </Box>
                              }
                            />
                          </ListItem>
                          {index < orderDetails.shipments.length - 1 && <Divider />}
                        </Box>
                      )
                    })}
                  </List>
                </>
              )}
            </Box>
          ) : (
            <Alert severity="error">
              Failed to load order details
            </Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button
            onClick={() => {
              setOrderDetailsOpen(false)
              setSelectedOrder(null)
              setOrderDetails(null)
            }}
            variant="contained"
          >
            Close
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  )
}

export default SellerOrders
