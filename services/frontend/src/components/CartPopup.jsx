import {
  Popover,
  Box,
  Typography,
  Button,
  Divider,
  IconButton,
  List,
  ListItem,
  ListItemText,
  ListItemSecondaryAction,
  CircularProgress,
  Alert,
  Tooltip,
} from '@mui/material'
import DeleteIcon from '@mui/icons-material/Delete'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import ShoppingCartCheckoutIcon from '@mui/icons-material/ShoppingCartCheckout'
import { useState } from 'react'
import { useCart } from '../context/CartContext'
import { useNavigate } from 'react-router-dom'
import { catalogApiClient } from '../api/catalogApi'
import ProductModal from './ProductModal'

function CartPopup({ anchorEl, open, onClose }) {
  const { cartItems, updateQuantity, removeFromCart, getTotalPrice, getTotalItems, loading, error } = useCart()
  const navigate = useNavigate()
  const [selectedProduct, setSelectedProduct] = useState(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [loadingProduct, setLoadingProduct] = useState(false)

  const handleCheckout = () => {
    onClose()
    navigate('/checkout')
  }

  const handleProductClick = async (productId, event) => {
    // Don't open modal if clicking on quantity controls or delete button
    if (event.target.closest('button') || event.target.closest('[role="button"]')) {
      return
    }

    setLoadingProduct(true)
    try {
      const product = await catalogApiClient.getProduct(productId)
      setSelectedProduct(product)
      setModalOpen(true)
    } catch (err) {
      console.error('Error fetching product details:', err)
    } finally {
      setLoadingProduct(false)
    }
  }

  const handleCloseModal = () => {
    setModalOpen(false)
    setSelectedProduct(null)
  }

  const formatPrice = (cents, currency = 'CAD') => {
    const currencyCode = currency
    return new Intl.NumberFormat('en-CA', {
      style: 'currency',
      currency: currencyCode,
    }).format(cents / 100)
  }

  const totalItems = getTotalItems()
  const totalPrice = getTotalPrice()
  // Use the first item's currency, or default to CAD
  const currency = cartItems.length > 0 && cartItems[0].currency ? cartItems[0].currency : 'CAD'

  return (
    <Popover
      open={open}
      anchorEl={anchorEl}
      onClose={onClose}
      anchorOrigin={{
        vertical: 'bottom',
        horizontal: 'right',
      }}
      transformOrigin={{
        vertical: 'top',
        horizontal: 'right',
      }}
      PaperProps={{
        sx: {
          width: 400,
          maxWidth: '90vw',
          maxHeight: '80vh',
          display: 'flex',
          flexDirection: 'column',
        },
      }}
    >
      <Box sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }}>
        <Typography variant="h6" component="div">
          Shopping Cart
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {totalItems} {totalItems === 1 ? 'item' : 'items'}
        </Typography>
      </Box>

      <Box sx={{ flex: 1, overflow: 'auto', minHeight: 200, maxHeight: 400 }}>
        {error && (
          <Box sx={{ p: 2 }}>
            <Alert severity="error" onClose={() => {}}>
              {error}
            </Alert>
          </Box>
        )}
        {cartItems.length === 0 ? (
          <Box sx={{ p: 4, textAlign: 'center' }}>
            <Typography variant="body2" color="text.secondary">
              {loading ? 'Loading cart...' : 'Your cart is empty'}
            </Typography>
          </Box>
        ) : (
          <List sx={{ p: 0 }}>
            {cartItems.map((item, index) => (
              <Box key={item.productId}>
                <ListItem
                  sx={{
                    py: 2,
                    px: 2,
                    cursor: 'pointer',
                    '&:hover': {
                      backgroundColor: 'action.hover',
                    },
                  }}
                  onClick={(e) => handleProductClick(item.productId, e)}
                >
                  {item.productImage && (
                    <Box
                      component="img"
                      src={item.productImage}
                      alt={item.productName}
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
                      <Tooltip title={item.productName} arrow placement="top">
                        <Typography
                          variant="body2"
                          sx={{
                            fontWeight: 'medium',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            maxWidth: '200px',
                            cursor: 'help',
                          }}
                        >
                          {item.productName}
                        </Typography>
                      </Tooltip>
                    }
                    secondary={
                      <Box>
                        <Typography variant="caption" color="text.secondary">
                          {formatPrice(item.priceCents, item.currency)} each
                        </Typography>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1 }} onClick={(e) => e.stopPropagation()}>
                          <IconButton
                            size="small"
                            onClick={(e) => {
                              e.stopPropagation()
                              updateQuantity(item.productId, item.quantity - 1)
                            }}
                            disabled={loading}
                          >
                            <RemoveIcon fontSize="small" />
                          </IconButton>
                          <Typography variant="body2" sx={{ minWidth: 30, textAlign: 'center' }}>
                            {item.quantity}
                          </Typography>
                          <IconButton
                            size="small"
                            onClick={(e) => {
                              e.stopPropagation()
                              updateQuantity(item.productId, item.quantity + 1)
                            }}
                            disabled={loading}
                          >
                            <AddIcon fontSize="small" />
                          </IconButton>
                        </Box>
                      </Box>
                    }
                  />
                  <ListItemSecondaryAction onClick={(e) => e.stopPropagation()}>
                    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 1 }}>
                      <Typography variant="body2" sx={{ fontWeight: 'medium' }}>
                        {formatPrice(item.priceCents * item.quantity, item.currency)}
                      </Typography>
                      <IconButton
                        edge="end"
                        size="small"
                        onClick={(e) => {
                          e.stopPropagation()
                          removeFromCart(item.productId)
                        }}
                        disabled={loading}
                        color="error"
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Box>
                  </ListItemSecondaryAction>
                </ListItem>
                {index < cartItems.length - 1 && <Divider />}
              </Box>
            ))}
          </List>
        )}
      </Box>

      {cartItems.length > 0 && (
        <>
          <Divider />
          <Box sx={{ p: 2 }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
              <Typography variant="h6">Total</Typography>
              <Typography variant="h6" sx={{ fontWeight: 'bold' }}>
                {formatPrice(totalPrice, currency)}
              </Typography>
            </Box>
            <Button
              variant="contained"
              fullWidth
              size="large"
              startIcon={loading ? <CircularProgress size={20} color="inherit" /> : <ShoppingCartCheckoutIcon />}
              onClick={handleCheckout}
              disabled={loading || cartItems.length === 0}
              sx={{ py: 1.5 }}
            >
              Checkout
            </Button>
          </Box>
        </>
      )}
      <ProductModal open={modalOpen} onClose={handleCloseModal} product={selectedProduct} />
    </Popover>
  )
}

export default CartPopup

