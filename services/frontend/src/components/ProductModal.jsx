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
  Breadcrumbs,
  Link,
  CircularProgress,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import ArrowBackIosIcon from '@mui/icons-material/ArrowBackIos'
import ArrowForwardIosIcon from '@mui/icons-material/ArrowForwardIos'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import NavigateNextIcon from '@mui/icons-material/NavigateNext'
import HomeIcon from '@mui/icons-material/Home'
import { useState, useEffect } from 'react'
import { useCart } from '../context/CartContext'
import { useNavigate } from 'react-router-dom'
import { useAuth0 } from '../auth/Auth0Provider'
import { catalogApiClient } from '../api/catalogApi'
import { cacheProduct } from '../utils/productCache'

function ProductModal({ open, onClose, product }) {
  const navigate = useNavigate()
  const { isAuthenticated, getAccessToken } = useAuth0()
  const [currentImageIndex, setCurrentImageIndex] = useState(0)
  const [quantity, setQuantity] = useState(1)
  const [categoryBreadcrumbs, setCategoryBreadcrumbs] = useState([])
  const [loadingCategory, setLoadingCategory] = useState(false)
  const [addingToCart, setAddingToCart] = useState(false)
  const { addToCart, isInCart, getQuantity } = useCart()

  // Load category breadcrumbs when product changes
  useEffect(() => {
    const loadCategoryBreadcrumbs = async () => {
      if (!product?.category_id || !isAuthenticated) {
        setCategoryBreadcrumbs([])
        return
      }

      setLoadingCategory(true)
      try {
        const token = await getAccessToken()
        const categories = await catalogApiClient.getCategories(token)
        const categoryMap = new Map()
        // Store all categories with string keys for consistent lookup
        categories.forEach(cat => {
          categoryMap.set(String(cat.id), cat)
        })

        // Build breadcrumb path
        const breadcrumbs = []
        let currentCategoryId = String(product.category_id)
        let currentCategory = categoryMap.get(currentCategoryId)
        
        // Traverse up the hierarchy to build breadcrumbs
        while (currentCategory) {
          breadcrumbs.unshift(currentCategory) // Add to beginning
          if (currentCategory.parent_id) {
            currentCategoryId = String(currentCategory.parent_id)
            currentCategory = categoryMap.get(currentCategoryId)
          } else {
            break
          }
        }

        setCategoryBreadcrumbs(breadcrumbs)
      } catch (err) {
        console.warn('Failed to load category breadcrumbs:', err)
        setCategoryBreadcrumbs([])
      } finally {
        setLoadingCategory(false)
      }
    }

    if (open && product) {
      loadCategoryBreadcrumbs()
    }
  }, [open, product?.category_id, product?.id, isAuthenticated, getAccessToken])

  // Cache product info when modal opens
  useEffect(() => {
    if (product) {
      cacheProduct(product)
    }
  }, [product])

  if (!product) return null

  const images = product.images || []
  const hasMultipleImages = images.length > 1
  const price = (product.price_cents / 100).toFixed(2)
  const inCart = isInCart(product.id)
  const cartQuantity = getQuantity(product.id)
  // Check if product is out of stock (treat null inventory as 0)
  const availableQty = product.inventory?.available_quantity ?? product.inventory?.availableQuantity ?? 0
  const isOutOfStock = availableQty <= 0
  const LOW_STOCK_THRESHOLD = 10 // Products with <= 10 available are considered low stock
  const isLowStock = availableQty > 0 && availableQty <= LOW_STOCK_THRESHOLD

  const handleCategoryClick = (categoryId) => {
    onClose()
    // Navigate to home with category filter applied
    // Convert to string to ensure consistency
    const categoryIdStr = categoryId ? String(categoryId) : null
    navigate('/', { state: { categoryId: categoryIdStr } })
  }

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

  const handleAddToCart = async () => {
    setAddingToCart(true)
    try {
      await addToCart(product, quantity)
      setQuantity(1)
      // Optionally show a success message or close modal
    } catch (err) {
      console.error('Failed to add to cart:', err)
      // Error is already handled in CartContext
    } finally {
      setAddingToCart(false)
    }
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
      <DialogTitle>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 1 }}>
          <Box sx={{ flex: 1 }}>
            {categoryBreadcrumbs.length > 0 && (
              <Breadcrumbs
                separator={<NavigateNextIcon fontSize="small" />}
                sx={{ mb: 1 }}
                aria-label="category breadcrumb"
              >
                <Link
                  component="button"
                  variant="body2"
                  onClick={() => handleCategoryClick(null)}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 0.5,
                    color: 'text.secondary',
                    textDecoration: 'none',
                    '&:hover': {
                      textDecoration: 'underline',
                      color: 'primary.main',
                    },
                    cursor: 'pointer',
                  }}
                >
                  <HomeIcon fontSize="small" />
                  All Products
                </Link>
                {categoryBreadcrumbs.map((category, index) => (
                  <Link
                    key={category.id}
                    component="button"
                    variant="body2"
                    onClick={() => handleCategoryClick(category.id)}
                    sx={{
                      color: index === categoryBreadcrumbs.length - 1 ? 'text.primary' : 'text.secondary',
                      textDecoration: 'none',
                      fontWeight: index === categoryBreadcrumbs.length - 1 ? 600 : 400,
                      '&:hover': {
                        textDecoration: 'underline',
                        color: 'primary.main',
                      },
                      cursor: 'pointer',
                    }}
                  >
                    {category.name}
                  </Link>
                ))}
              </Breadcrumbs>
            )}
            <Typography variant="h5" component="div">
              {product.name}
            </Typography>
          </Box>
          <IconButton onClick={handleClose} size="small" sx={{ ml: 2 }}>
            <CloseIcon />
          </IconButton>
        </Box>
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
                {isOutOfStock ? (
                  <Box sx={{ textAlign: 'center', py: 2 }}>
                    <Chip
                      label="Out of Stock"
                      color="error"
                      sx={{ mb: 2, fontWeight: 600 }}
                    />
                    <Typography variant="body2" color="text.secondary">
                      This product is currently unavailable
                    </Typography>
                  </Box>
                ) : (
                  <>
                    {isLowStock && (
                      <Box sx={{ mb: 2, p: 1.5, backgroundColor: 'warning.light', borderRadius: 1, border: '1px solid', borderColor: 'warning.main' }}>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                          <Chip
                            label="Low Stock"
                            color="warning"
                            size="small"
                            sx={{ fontWeight: 600 }}
                          />
                        </Box>
                        <Typography variant="body2" color="warning.dark" sx={{ fontWeight: 500 }}>
                          Only {availableQty} {availableQty === 1 ? 'item' : 'items'} remaining. Order soon!
                        </Typography>
                      </Box>
                    )}
                    <Box sx={{ display: 'flex', gap: 2, alignItems: 'center', mb: 2 }}>
                      <TextField
                        label="Quantity"
                        type="number"
                        value={quantity}
                        onChange={handleQuantityChange}
                        inputProps={{ min: 1, max: availableQty }}
                        sx={{ width: 100 }}
                        size="small"
                        helperText={availableQty > 0 ? `${availableQty} available` : ''}
                        disabled={isOutOfStock || addingToCart}
                        error={isLowStock}
                      />
                      <Button
                        variant="contained"
                        startIcon={addingToCart ? <CircularProgress size={20} color="inherit" /> : <ShoppingCartIcon />}
                        onClick={handleAddToCart}
                        fullWidth
                        size="large"
                        disabled={isOutOfStock || quantity > availableQty || addingToCart}
                      >
                        {addingToCart ? 'Adding to Cart...' : (inCart ? `Add More (${cartQuantity} in cart)` : 'Add to Cart')}
                      </Button>
                    </Box>
                    {inCart && (
                      <Typography variant="body2" color="success.main" sx={{ textAlign: 'center', mt: 1 }}>
                        ✓ {cartQuantity} item{cartQuantity > 1 ? 's' : ''} in your cart
                      </Typography>
                    )}
                  </>
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

