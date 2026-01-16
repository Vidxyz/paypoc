import { useState, useEffect } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Box,
  Button,
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
  IconButton,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  MenuItem,
  TablePagination,
} from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import EditIcon from '@mui/icons-material/Edit'
import DeleteIcon from '@mui/icons-material/Delete'
import InventoryIcon from '@mui/icons-material/Inventory'
import { useAuth0 } from '../auth/Auth0Provider'
import { createCatalogApiClient } from '../api/catalogApi'
import { createInventoryApiClient } from '../api/inventoryApi'
import { useNavigate } from 'react-router-dom'

function Products() {
  const { isAuthenticated, getAccessToken } = useAuth0()
  const navigate = useNavigate()
  const [products, setProducts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [inventoryData, setInventoryData] = useState({}) // productId -> inventory info
  const [inventoryLoading, setInventoryLoading] = useState({})
  const [categories, setCategories] = useState({}) // categoryId -> category object
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(20)
  const [totalProducts, setTotalProducts] = useState(0)
  
  // Inventory update dialog state
  const [inventoryDialogOpen, setInventoryDialogOpen] = useState(false)
  const [selectedProduct, setSelectedProduct] = useState(null)
  const [stockQuantity, setStockQuantity] = useState(0)
  const [stockSku, setStockSku] = useState('')

  useEffect(() => {
    if (!isAuthenticated) {
      return
    }

    const fetchProducts = async () => {
      try {
        setLoading(true)
        setError(null)

        const catalogApi = createCatalogApiClient(getAccessToken)
        
        // Fetch categories first (only once)
        if (Object.keys(categories).length === 0) {
          const categoriesData = await catalogApi.getCategories()
          const categoryMap = {}
          if (Array.isArray(categoriesData)) {
            categoriesData.forEach(cat => {
              categoryMap[cat.id] = cat
            })
          }
          setCategories(categoryMap)
        }
        
        // Fetch products with pagination (page is 0-indexed in UI, 1-indexed in API)
        const productsResponse = await catalogApi.getProducts({
          page: page + 1,
          page_size: rowsPerPage
        })
        
        setProducts(productsResponse.products || [])
        setTotalProducts(productsResponse.total || 0)

        // Extract inventory from product responses (catalog service now includes it)
        const inventoryMap = {}
        const productsList = Array.isArray(productsResponse.products) ? productsResponse.products : []
        productsList.forEach((product) => {
          // Use product.id as string key to ensure consistent lookup
          const productId = String(product.id)
          if (product.inventory) {
            inventoryMap[productId] = product.inventory
          }
        })
        setInventoryData(inventoryMap)

        catalogApi.cleanup()
      } catch (err) {
        console.error('Failed to fetch products:', err)
        setError(err.message || 'Failed to load products')
      } finally {
        setLoading(false)
      }
    }

    fetchProducts()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated, page, rowsPerPage])

  const handleAddProduct = () => {
    navigate('/products/new')
  }

  const handleEditProduct = (productId) => {
    navigate(`/products/${productId}/edit`)
  }

  const handleDeleteProduct = async (productId) => {
    if (!window.confirm('Are you sure you want to delete this product?')) {
      return
    }

    try {
      const catalogApi = createCatalogApiClient(getAccessToken)
      await catalogApi.deleteProduct(productId)
      
      // Refresh products list
      const productsResponse = await catalogApi.getProducts({
        page: page + 1,
        page_size: rowsPerPage
      })
      setProducts(productsResponse.products || [])
      setTotalProducts(productsResponse.total || 0)
      
      catalogApi.cleanup()
    } catch (err) {
      console.error('Failed to delete product:', err)
      alert(`Failed to delete product: ${err.message}`)
    }
  }

  const handleChangePage = (event, newPage) => {
    setPage(newPage)
  }

  const handleChangeRowsPerPage = (event) => {
    setRowsPerPage(parseInt(event.target.value, 10))
    setPage(0) // Reset to first page when changing page size
  }

  const handleOpenInventoryDialog = (product) => {
    setSelectedProduct(product)
    const productId = String(product.id)
    const existingStock = inventoryData[productId] || product.inventory
    setStockQuantity(existingStock?.total_quantity ?? existingStock?.totalQuantity ?? 0)
    setStockSku(product.sku)
    setInventoryDialogOpen(true)
  }

  const handleCloseInventoryDialog = () => {
    setInventoryDialogOpen(false)
    setSelectedProduct(null)
    setStockQuantity(0)
    setStockSku('')
  }

  const handleUpdateInventory = async () => {
    if (!selectedProduct) return

    try {
      setInventoryLoading({ [selectedProduct.id]: true })
      const inventoryApi = createInventoryApiClient(getAccessToken)
      
      // Use existing inventory data from products response (no need to fetch again)
      const productId = String(selectedProduct.id)
      const existingStock = inventoryData[productId] || selectedProduct.inventory
      let updatedStock

      if (existingStock && existingStock.inventory_id) {
        // Update existing stock
        const inventoryId = existingStock.inventory_id
        const currentTotal = existingStock.total_quantity || 0
        const delta = stockQuantity - currentTotal
        if (delta !== 0) {
          updatedStock = await inventoryApi.adjustStock(inventoryId, delta)
        } else {
          // No change needed, use existing stock
          updatedStock = existingStock
        }
      } else {
        // Create new stock
        updatedStock = await inventoryApi.createOrUpdateStock(selectedProduct.id, {
          sku: stockSku,
          quantity: stockQuantity
        })
      }

      // Update inventory data with the response from the update operation
      // The update operations return the full inventory object (camelCase from Kotlin service)
      if (updatedStock) {
        // Normalize the response format to match catalog service format (snake_case)
        const normalizedStock = {
          inventory_id: updatedStock.id || updatedStock.inventory_id,
          available_quantity: updatedStock.availableQuantity ?? updatedStock.available_quantity ?? 0,
          reserved_quantity: updatedStock.reservedQuantity ?? updatedStock.reserved_quantity ?? 0,
          allocated_quantity: updatedStock.allocatedQuantity ?? updatedStock.allocated_quantity ?? 0,
          total_quantity: updatedStock.totalQuantity ?? updatedStock.total_quantity ?? 0,
          low_stock_threshold: updatedStock.lowStockThreshold ?? updatedStock.low_stock_threshold ?? null
        }
        setInventoryData(prev => ({
          ...prev,
          [productId]: normalizedStock
        }))
      }

      inventoryApi.cleanup()
      handleCloseInventoryDialog()
    } catch (err) {
      console.error('Failed to update inventory:', err)
      alert(`Failed to update inventory: ${err.message}`)
    } finally {
      setInventoryLoading({ [selectedProduct.id]: false })
    }
  }

  const formatAmount = (cents, currency = 'USD') => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency,
    }).format(cents / 100)
  }

  const getStatusColor = (status) => {
    switch (status) {
      case 'ACTIVE':
        return 'success'
      case 'DRAFT':
        return 'warning'
      case 'INACTIVE':
        return 'default'
      default:
        return 'default'
    }
  }

  // Get image URL from API (ensures correct cloud name and version)
  const getImageUrl = async (publicId, catalogApi) => {
    if (!publicId) return null
    try {
      const url = await catalogApi.getImageUrl(publicId)
      return url
    } catch (err) {
      console.warn(`Failed to get image URL for ${publicId}:`, err)
      return null
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
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 4, flexWrap: 'wrap', gap: 2 }}>
        <Typography 
          variant="h4" 
          component="h1"
          sx={{ 
            fontWeight: 700,
            background: 'linear-gradient(135deg, #4a90e2 0%, #3a7bc8 100%)',
            backgroundClip: 'text',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
          }}
        >
          My Products
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={handleAddProduct}
        >
          Add Product
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {error}
        </Alert>
      )}

      {products.length === 0 ? (
        <Card>
          <CardContent>
            <Alert severity="info">
              No products found. Click "Add Product" to create your first product.
            </Alert>
          </CardContent>
        </Card>
      ) : (
        <Paper variant="outlined">
          <TableContainer sx={{ maxHeight: 'calc(100vh - 300px)' }}>
            <Table stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell>Image</TableCell>
                  <TableCell>Name</TableCell>
                  <TableCell>SKU</TableCell>
                  <TableCell>Category</TableCell>
                  <TableCell>Price</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Stock</TableCell>
                  <TableCell>Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {products.map((product) => {
                  // Use string key for consistent lookup
                  const productId = String(product.id)
                  const stock = inventoryData[productId] || product.inventory
                  // Images are now full Cloudinary URLs directly from the API
                  const imageUrl = product.images && Array.isArray(product.images) && product.images.length > 0 
                    ? product.images[0] 
                    : null
                  
                  // Get category information
                  const category = product.category_id ? categories[product.category_id] : null
                  
                  return (
                    <TableRow 
                      key={product.id}
                      onClick={() => handleEditProduct(product.id)}
                      sx={{
                        cursor: 'pointer',
                        '&:hover': {
                          backgroundColor: 'action.hover',
                        },
                      }}
                    >
                      <TableCell>
                        <Box
                          sx={{
                            width: 60,
                            height: 60,
                            borderRadius: 1,
                            overflow: 'hidden',
                            bgcolor: 'grey.200',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                          }}
                        >
                          {imageUrl ? (
                            <Box
                              component="img"
                              src={imageUrl}
                              alt={product.name}
                              sx={{
                                width: '100%',
                                height: '100%',
                                objectFit: 'cover',
                              }}
                              onError={(e) => {
                                // On error, hide the image - the placeholder background will show
                                e.target.style.display = 'none'
                              }}
                            />
                          ) : null}
                          {!imageUrl && (
                            <Typography variant="caption" color="text.secondary">
                              No Image
                            </Typography>
                          )}
                        </Box>
                      </TableCell>
                      <TableCell>
                        <Typography 
                          variant="body2" 
                          sx={{ 
                            fontWeight: 'medium',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            maxWidth: '300px',
                          }}
                          title={product.name}
                        >
                          {product.name}
                        </Typography>
                        {product.description && (
                          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                            {product.description.substring(0, 50)}
                            {product.description.length > 50 ? '...' : ''}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {product.sku}
                        </Typography>
                      </TableCell>
                      <TableCell onClick={(e) => e.stopPropagation()}>
                        {category ? (
                          <Chip
                            label={category.name}
                            size="small"
                            color="primary"
                            variant="outlined"
                            sx={{ 
                              fontSize: '0.75rem',
                              height: '24px',
                              '& .MuiChip-label': {
                                px: 1,
                              }
                            }}
                          />
                        ) : (
                          <Typography variant="caption" color="text.secondary">
                            No category
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {formatAmount(product.price_cents, product.currency)}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={product.status}
                          color={getStatusColor(product.status)}
                          size="small"
                        />
                      </TableCell>
                      <TableCell>
                        {stock && (stock.available_quantity !== undefined || stock.total_quantity !== undefined) ? (
                          <Typography variant="body2">
                            {stock.available_quantity ?? stock.availableQuantity ?? 0} available
                            {stock.total_quantity !== undefined && (
                              <Typography component="span" variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                                Total: {stock.total_quantity ?? stock.totalQuantity ?? 0}
                              </Typography>
                            )}
                          </Typography>
                        ) : (
                          <Typography variant="body2" color="text.secondary">
                            Not set
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell onClick={(e) => e.stopPropagation()}>
                        <Box sx={{ display: 'flex', gap: 1 }}>
                          <IconButton
                            size="small"
                            onClick={() => handleOpenInventoryDialog(product)}
                            title="Update Inventory"
                          >
                            <InventoryIcon fontSize="small" />
                          </IconButton>
                          <IconButton
                            size="small"
                            onClick={() => handleEditProduct(product.id)}
                            title="Edit Product"
                          >
                            <EditIcon fontSize="small" />
                          </IconButton>
                          <IconButton
                            size="small"
                            onClick={() => handleDeleteProduct(product.id)}
                            title="Delete Product"
                            color="error"
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </Box>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={totalProducts}
            page={page}
            onPageChange={handleChangePage}
            rowsPerPage={rowsPerPage}
            onRowsPerPageChange={handleChangeRowsPerPage}
            rowsPerPageOptions={[10, 20, 50, 100]}
            labelRowsPerPage="Rows per page:"
          />
        </Paper>
      )}

      {/* Inventory Update Dialog */}
      <Dialog open={inventoryDialogOpen} onClose={handleCloseInventoryDialog} maxWidth="sm" fullWidth>
        <DialogTitle>Update Inventory</DialogTitle>
        <DialogContent>
          {selectedProduct && (
            <Box sx={{ pt: 2 }}>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Product: {selectedProduct.name}
              </Typography>
              <TextField
                fullWidth
                label="SKU"
                value={stockSku}
                onChange={(e) => setStockSku(e.target.value)}
                margin="normal"
                required
              />
              <TextField
                fullWidth
                label="Stock Quantity"
                type="number"
                value={stockQuantity}
                onChange={(e) => setStockQuantity(parseInt(e.target.value) || 0)}
                margin="normal"
                required
                inputProps={{ min: 0 }}
              />
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseInventoryDialog}>Cancel</Button>
          <Button
            onClick={handleUpdateInventory}
            variant="contained"
            disabled={inventoryLoading[selectedProduct?.id] || !stockSku || stockQuantity < 0}
          >
            {inventoryLoading[selectedProduct?.id] ? <CircularProgress size={24} /> : 'Update'}
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  )
}

export default Products

