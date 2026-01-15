import {
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Box,
  Typography,
  Button,
  TextField,
  Grid,
  Paper,
  Chip,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import ArrowBackIosIcon from '@mui/icons-material/ArrowBackIos'
import ArrowForwardIosIcon from '@mui/icons-material/ArrowForwardIos'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import { useState } from 'react'
import { useCart } from '../context/CartContext'

function ProductModal({ open, onClose, product }) {
  const [currentImageIndex, setCurrentImageIndex] = useState(0)
  const [quantity, setQuantity] = useState(1)
  const { addToCart, isInCart, getQuantity } = useCart()

  if (!product) return null

  const images = product.images || []
  const hasMultipleImages = images.length > 1
  const price = (product.price_cents / 100).toFixed(2)
  const inCart = isInCart(product.id)
  const cartQuantity = getQuantity(product.id)

  const handlePreviousImage = () => {
    setCurrentImageIndex((prev) => (prev === 0 ? images.length - 1 : prev - 1))
  }

  const handleNextImage = () => {
    setCurrentImageIndex((prev) => (prev === images.length - 1 ? 0 : prev + 1))
  }

  const handleQuantityChange = (event) => {
    const value = parseInt(event.target.value) || 1
    setQuantity(Math.max(1, value))
  }

  const handleAddToCart = () => {
    addToCart(product, quantity)
    setQuantity(1)
    // Optionally show a success message or close modal
  }

  const handleClose = () => {
    setCurrentImageIndex(0)
    setQuantity(1)
    onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="md"
      fullWidth
      PaperProps={{
        sx: {
          borderRadius: 2,
          maxHeight: '90vh',
        },
      }}
    >
      <DialogTitle sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="div">
          {product.name}
        </Typography>
        <IconButton onClick={handleClose} size="small">
          <CloseIcon />
        </IconButton>
      </DialogTitle>

      <DialogContent dividers>
        <Grid container spacing={3}>
          {/* Image Section */}
          <Grid item xs={12} md={6}>
            <Box
              sx={{
                position: 'relative',
                width: '100%',
                aspectRatio: '1',
                borderRadius: 2,
                overflow: 'hidden',
                backgroundColor: 'grey.100',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              {images.length > 0 ? (
                <>
                  <Box
                    component="img"
                    src={images[currentImageIndex]}
                    alt={product.name}
                    sx={{
                      width: '100%',
                      height: '100%',
                      objectFit: 'cover',
                    }}
                    onError={(e) => {
                      e.target.src = 'https://via.placeholder.com/400?text=Image+Not+Available'
                    }}
                  />
                  {hasMultipleImages && (
                    <>
                      <IconButton
                        onClick={handlePreviousImage}
                        sx={{
                          position: 'absolute',
                          left: 8,
                          backgroundColor: 'rgba(255, 255, 255, 0.8)',
                          '&:hover': { backgroundColor: 'rgba(255, 255, 255, 0.9)' },
                        }}
                      >
                        <ArrowBackIosIcon />
                      </IconButton>
                      <IconButton
                        onClick={handleNextImage}
                        sx={{
                          position: 'absolute',
                          right: 8,
                          backgroundColor: 'rgba(255, 255, 255, 0.8)',
                          '&:hover': { backgroundColor: 'rgba(255, 255, 255, 0.9)' },
                        }}
                      >
                        <ArrowForwardIosIcon />
                      </IconButton>
                      {/* Image indicators */}
                      <Box
                        sx={{
                          position: 'absolute',
                          bottom: 8,
                          left: '50%',
                          transform: 'translateX(-50%)',
                          display: 'flex',
                          gap: 1,
                        }}
                      >
                        {images.map((_, index) => (
                          <Box
                            key={index}
                            sx={{
                              width: 8,
                              height: 8,
                              borderRadius: '50%',
                              backgroundColor: index === currentImageIndex ? 'primary.main' : 'rgba(255, 255, 255, 0.5)',
                              cursor: 'pointer',
                            }}
                            onClick={() => setCurrentImageIndex(index)}
                          />
                        ))}
                      </Box>
                    </>
                  )}
                </>
              ) : (
                <Typography color="text.secondary">No image available</Typography>
              )}
            </Box>

            {/* Thumbnail images */}
            {hasMultipleImages && images.length > 1 && (
              <Box sx={{ display: 'flex', gap: 1, mt: 2, overflowX: 'auto' }}>
                {images.map((image, index) => (
                  <Box
                    key={index}
                    onClick={() => setCurrentImageIndex(index)}
                    sx={{
                      width: 60,
                      height: 60,
                      borderRadius: 1,
                      overflow: 'hidden',
                      cursor: 'pointer',
                      border: index === currentImageIndex ? 2 : 1,
                      borderColor: index === currentImageIndex ? 'primary.main' : 'grey.300',
                      flexShrink: 0,
                    }}
                  >
                    <Box
                      component="img"
                      src={image}
                      alt={`${product.name} ${index + 1}`}
                      sx={{
                        width: '100%',
                        height: '100%',
                        objectFit: 'cover',
                      }}
                    />
                  </Box>
                ))}
              </Box>
            )}
          </Grid>

          {/* Product Details Section */}
          <Grid item xs={12} md={6}>
            <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
              <Box sx={{ flexGrow: 1 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                  <Typography variant="h4" color="primary" fontWeight="bold">
                    ${price}
                  </Typography>
                  <Chip label={product.currency} size="small" />
                </Box>

                {product.sku && (
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    SKU: {product.sku}
                  </Typography>
                )}

                {product.description && (
                  <Paper sx={{ p: 2, mb: 3, backgroundColor: 'grey.50' }}>
                    <Typography variant="body1" component="div" sx={{ whiteSpace: 'pre-wrap' }}>
                      {product.description}
                    </Typography>
                  </Paper>
                )}

                {product.attributes && Object.keys(product.attributes).length > 0 && (
                  <Box sx={{ mb: 3 }}>
                    <Typography variant="subtitle2" fontWeight="bold" sx={{ mb: 1 }}>
                      Product Details:
                    </Typography>
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                      {Object.entries(product.attributes).map(([key, value]) => (
                        <Chip
                          key={key}
                          label={`${key}: ${value}`}
                          size="small"
                          variant="outlined"
                        />
                      ))}
                    </Box>
                  </Box>
                )}
              </Box>

              {/* Add to Cart Section */}
              <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 2, mt: 'auto' }}>
                <Box sx={{ display: 'flex', gap: 2, alignItems: 'center', mb: 2 }}>
                  <TextField
                    label="Quantity"
                    type="number"
                    value={quantity}
                    onChange={handleQuantityChange}
                    inputProps={{ min: 1 }}
                    sx={{ width: 100 }}
                    size="small"
                  />
                  <Button
                    variant="contained"
                    startIcon={<ShoppingCartIcon />}
                    onClick={handleAddToCart}
                    fullWidth
                    size="large"
                  >
                    {inCart ? `Add More (${cartQuantity} in cart)` : 'Add to Cart'}
                  </Button>
                </Box>
                {inCart && (
                  <Typography variant="body2" color="success.main" sx={{ textAlign: 'center' }}>
                    ✓ {cartQuantity} item{cartQuantity > 1 ? 's' : ''} in your cart
                  </Typography>
                )}
              </Box>
            </Box>
          </Grid>
        </Grid>
      </DialogContent>
    </Dialog>
  )
}

export default ProductModal

