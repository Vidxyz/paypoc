import { useState, useEffect, useMemo, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import {
  Container,
  Typography,
  Box,
  Grid,
  Card,
  CardMedia,
  CardContent,
  CardActionArea,
  CircularProgress,
  Pagination,
  Alert,
  Button,
  Paper,
  List,
  ListItem,
  ListItemButton,
  ListItemText,
  Chip,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  TextField,
  InputAdornment,
} from '@mui/material'
import SearchIcon from '@mui/icons-material/Search'
import { catalogApiClient } from '../api/catalogApi'
import ProductModal from '../components/ProductModal'
import { useAuth0 } from '../auth/Auth0Provider'
import { cacheProducts } from '../utils/productCache'

function Home() {
  const { isAuthenticated, getAccessToken } = useAuth0()
  const location = useLocation()
  const [products, setProducts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [page, setPage] = useState(1)
  const [totalPages, setTotalPages] = useState(1)
  const [totalProducts, setTotalProducts] = useState(0)
  const [selectedProduct, setSelectedProduct] = useState(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [categories, setCategories] = useState([])
  const [selectedCategoryIds, setSelectedCategoryIds] = useState(new Set())
  const [categoriesLoading, setCategoriesLoading] = useState(true)
  const [sortBy, setSortBy] = useState('newest') // 'newest', 'availability'
  const [searchQuery, setSearchQuery] = useState('')
  const [debouncedSearchQuery, setDebouncedSearchQuery] = useState('')
  const searchTimeoutRef = useRef(null)
  const pageSize = 20
  const LOW_STOCK_THRESHOLD = 10 // Products with <= 10 available are considered low stock

  // Apply category filter from navigation state
  useEffect(() => {
    if (location.state?.categoryId) {
      const categoryId = String(location.state.categoryId)
      setSelectedCategoryIds(new Set([categoryId]))
      setPage(1)
      // Clear the state to avoid re-applying on re-render
      window.history.replaceState({}, document.title)
    }
  }, [location.state])

  // Load categories on mount
  useEffect(() => {
    if (!isAuthenticated) {
      setCategoriesLoading(false)
      return
    }
    
    const loadCategories = async () => {
      try {
        const token = await getAccessToken()
        const categoriesData = await catalogApiClient.getCategories(token)
        setCategories(categoriesData || [])
      } catch (err) {
        console.warn('Failed to load categories:', err)
      } finally {
        setCategoriesLoading(false)
      }
    }
    loadCategories()
  }, [isAuthenticated, getAccessToken])

  // Organize categories hierarchically
  const organizedCategories = useMemo(() => {
    if (!categories.length) return []
    
    const topLevel = []
    const processedSubcategories = new Set()
    
    categories.forEach(cat => {
      const parentId = cat.parent_id ? String(cat.parent_id) : null
      if (!parentId) {
        // Top-level category
        topLevel.push(cat)
        const parentIdStr = String(cat.id)
        
        // Find and add its subcategories immediately after
        const subcategories = categories.filter(c => {
          const cParentId = c.parent_id ? String(c.parent_id) : null
          return cParentId === parentIdStr && !processedSubcategories.has(String(c.id))
        })
        
        subcategories.forEach(subcat => {
          topLevel.push(subcat)
          processedSubcategories.add(String(subcat.id))
        })
      }
    })
    
    return topLevel
  }, [categories])

  // Debounce search query
  useEffect(() => {
    if (searchTimeoutRef.current) {
      clearTimeout(searchTimeoutRef.current)
    }
    
    searchTimeoutRef.current = setTimeout(() => {
      setDebouncedSearchQuery(searchQuery)
    }, 500) // 500ms debounce delay
    
    return () => {
      if (searchTimeoutRef.current) {
        clearTimeout(searchTimeoutRef.current)
      }
    }
  }, [searchQuery])

  // Fetch products when page, categories, sort, or debounced search query change
  useEffect(() => {
    const categoryIdsArray = selectedCategoryIds.size > 0 ? Array.from(selectedCategoryIds) : null
    fetchProducts(page, categoryIdsArray, sortBy, debouncedSearchQuery)
  }, [page, selectedCategoryIds, sortBy, debouncedSearchQuery])

  const fetchProducts = async (currentPage, categoryIds = null, sortOption = 'newest', search = '') => {
    setLoading(true)
    setError(null)
    try {
      const response = await catalogApiClient.browseProducts({
        category_ids: categoryIds,
        page: currentPage,
        page_size: pageSize,
        sort_by: sortOption,
        search_query: search || undefined, // Only include if not empty
      })
      const products = response.products || []
      setProducts(products)
      setTotalProducts(response.total || 0)
      // Calculate total pages
      const pages = Math.ceil((response.total || 0) / pageSize)
      setTotalPages(pages > 0 ? pages : 1)
      
      // Cache product info for cart use
      cacheProducts(products)
    } catch (err) {
      console.error('Error fetching products:', err)
      setError(err.message || 'Failed to load products')
    } finally {
      setLoading(false)
    }
  }

  const handleCategoryToggle = (categoryId) => {
    setSelectedCategoryIds(prev => {
      const newSet = new Set(prev)
      if (newSet.has(categoryId)) {
        newSet.delete(categoryId)
      } else {
        newSet.add(categoryId)
      }
      return newSet
    })
    setPage(1) // Reset to first page when category changes
  }

  const handleClearFilter = () => {
    setSelectedCategoryIds(new Set())
    setSearchQuery('')
    setPage(1)
  }

  const handleSearchChange = (event) => {
    const value = event.target.value
    setSearchQuery(value)
    setPage(1) // Reset to first page when search changes
  }

  const handleProductClick = async (productId) => {
    try {
      const product = await catalogApiClient.getProduct(productId)
      setSelectedProduct(product)
      setModalOpen(true)
      // Cache product info for cart use
      cacheProducts([product])
    } catch (err) {
      console.error('Error fetching product details:', err)
      setError(err.message || 'Failed to load product details')
    }
  }

  const handlePageChange = (event, value) => {
    setPage(value)
    // Scroll to top when page changes
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const handleCloseModal = () => {
    setModalOpen(false)
    setSelectedProduct(null)
  }

  const selectedCategories = Array.from(selectedCategoryIds).map(id => 
    categories.find(c => String(c.id) === id)
  ).filter(Boolean)

  return (
    <Container maxWidth="xl" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <Box sx={{ mb: 4, textAlign: { xs: 'center', sm: 'left' } }}>
        <Typography 
          variant="h3" 
          component="h1" 
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
          Discover Amazing Products
        </Typography>
        
        {/* Search Bar */}
        <Box sx={{ mb: 3 }}>
          <TextField
            fullWidth
            placeholder="Search products..."
            value={searchQuery}
            onChange={handleSearchChange}
            variant="outlined"
            sx={{
              '& .MuiOutlinedInput-root': {
                borderRadius: 2,
                backgroundColor: 'background.paper',
              },
            }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon color="action" />
                </InputAdornment>
              ),
            }}
          />
        </Box>

        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
          <Typography variant="h6" color="text.secondary" sx={{ fontWeight: 400 }}>
            {selectedCategories.length > 0
              ? `Showing ${totalProducts} product${totalProducts !== 1 ? 's' : ''} in ${selectedCategories.length} categor${selectedCategories.length !== 1 ? 'ies' : 'y'}`
              : debouncedSearchQuery
              ? `Found ${totalProducts} product${totalProducts !== 1 ? 's' : ''} for "${debouncedSearchQuery}"`
              : `Browse our collection of ${totalProducts} carefully curated products`
            }
          </Typography>
          {(selectedCategories.length > 0 || debouncedSearchQuery) && (
            <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
              {selectedCategories.map(category => (
                <Chip
                  key={category.id}
                  label={category.name}
                  onDelete={() => handleCategoryToggle(String(category.id))}
                  color="primary"
                  variant="outlined"
                />
              ))}
              {debouncedSearchQuery && (
                <Chip
                  label={`Search: ${debouncedSearchQuery}`}
                  onDelete={() => setSearchQuery('')}
                  color="secondary"
                  variant="outlined"
                />
              )}
              <Chip
                label="Clear All"
                onClick={handleClearFilter}
                color="default"
                variant="outlined"
                sx={{ cursor: 'pointer' }}
              />
            </Box>
          )}
        </Box>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 3 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Grid container spacing={3}>
        {/* Categories Sidebar */}
        <Grid item xs={12} md={3}>
          <Paper 
            sx={{ 
              p: 2, 
              position: 'sticky', 
              top: 20,
              maxHeight: 'calc(100vh - 40px)',
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden',
            }}
          >
            <Typography variant="h6" gutterBottom sx={{ fontWeight: 600, mb: 2, flexShrink: 0 }}>
              Categories
            </Typography>
            {categoriesLoading ? (
              <Box sx={{ display: 'flex', justifyContent: 'center', py: 2, flexShrink: 0 }}>
                <CircularProgress size={24} />
              </Box>
            ) : (
              <Box sx={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
                <List dense sx={{ p: 0 }}>
                {organizedCategories.map((category, index) => {
                  const isSubcategory = category.parent_id !== null && category.parent_id !== undefined
                  const categoryId = String(category.id)
                  const isSelected = selectedCategoryIds.has(categoryId)
                  
                  return (
                    <ListItem key={categoryId} disablePadding>
                      <ListItemButton
                        onClick={() => handleCategoryToggle(categoryId)}
                        selected={isSelected}
                        sx={{
                          pl: isSubcategory ? 4 : 2,
                          borderRadius: 1,
                          mb: 0.5,
                          '&.Mui-selected': {
                            backgroundColor: 'primary.main',
                            color: 'primary.contrastText',
                            '&:hover': {
                              backgroundColor: 'primary.dark',
                            },
                          },
                        }}
                      >
                        <ListItemText 
                          primary={isSubcategory ? `└─ ${category.name}` : category.name}
                          primaryTypographyProps={{
                            fontWeight: isSelected ? 600 : 400,
                          }}
                        />
                      </ListItemButton>
                    </ListItem>
                  )
                })}
                </List>
              </Box>
            )}
          </Paper>
        </Grid>

        {/* Products Grid */}
        <Grid item xs={12} md={9}>
          {/* Sort and Filter Controls */}
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
            <Typography variant="body2" color="text.secondary">
              {totalProducts} product{totalProducts !== 1 ? 's' : ''} found
            </Typography>
            <FormControl size="small" sx={{ minWidth: 200 }}>
              <InputLabel>Sort By</InputLabel>
              <Select
                value={sortBy}
                label="Sort By"
                onChange={(e) => {
                  setSortBy(e.target.value)
                  setPage(1) // Reset to first page when sorting changes
                }}
              >
                <MenuItem value="newest">Newest First</MenuItem>
                <MenuItem value="availability">Availability (In Stock First)</MenuItem>
              </Select>
            </FormControl>
          </Box>
          
          {loading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '400px' }}>
              <CircularProgress />
            </Box>
          ) : products.length === 0 ? (
            <Box sx={{ textAlign: 'center', py: 8 }}>
              <Typography variant="h5" color="text.secondary" gutterBottom>
                No products available
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                {selectedCategories.length > 0
                  ? `No products found in selected categor${selectedCategories.length !== 1 ? 'ies' : 'y'}`
                  : 'Check back later for new products'
                }
              </Typography>
              {selectedCategories.length > 0 && (
                <Button variant="outlined" onClick={handleClearFilter}>
                  View All Products
                </Button>
              )}
            </Box>
          ) : (
            <>
              <Grid container spacing={3} sx={{ mb: 4 }}>
                {products.map((product) => (
              <Grid item xs={12} sm={6} md={4} lg={3} key={product.id}>
                <Card
                  sx={{
                    height: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
                    border: '1px solid',
                    borderColor: 'divider',
                    '&:hover': {
                      transform: 'translateY(-8px)',
                      boxShadow: '0 12px 24px rgba(0, 0, 0, 0.15)',
                      borderColor: 'primary.main',
                    },
                  }}
                >
                  <CardActionArea
                    onClick={() => handleProductClick(product.id)}
                    sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}
                  >
                    <Box sx={{ position: 'relative' }}>
                      <CardMedia
                        component="img"
                        height="240"
                        image={product.images?.[0] || 'https://via.placeholder.com/300?text=No+Image'}
                        alt={product.name}
                        sx={{
                          objectFit: 'cover',
                          backgroundColor: 'grey.100',
                          transition: 'transform 0.3s ease-in-out',
                          opacity: (() => {
                            const availableQty = product.inventory?.available_quantity ?? product.inventory?.availableQuantity ?? 0
                            return availableQty > 0 ? 1 : 0.6
                          })(),
                          '&:hover': {
                            transform: 'scale(1.05)',
                          },
                        }}
                        onError={(e) => {
                          e.target.src = 'https://via.placeholder.com/300?text=Image+Not+Available'
                        }}
                      />
                      {(() => {
                        const availableQty = product.inventory?.available_quantity ?? product.inventory?.availableQuantity ?? 0
                        if (availableQty <= 0) {
                          return (
                            <Chip
                              label="Out of Stock"
                              color="error"
                              size="small"
                              sx={{
                                position: 'absolute',
                                top: 8,
                                right: 8,
                                fontWeight: 600,
                              }}
                            />
                          )
                        } else if (availableQty <= LOW_STOCK_THRESHOLD) {
                          return (
                            <Chip
                              label={`Low Stock (${availableQty})`}
                              color="warning"
                              size="small"
                              sx={{
                                position: 'absolute',
                                top: 8,
                                right: 8,
                                fontWeight: 600,
                              }}
                            />
                          )
                        }
                        return null
                      })()}
                    </Box>
                    <CardContent sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column' }}>
                      <Typography
                        variant="h6"
                        component="h3"
                        sx={{
                          mb: 1,
                          fontWeight: 600,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          display: '-webkit-box',
                          WebkitLineClamp: 2,
                          WebkitBoxOrient: 'vertical',
                        }}
                      >
                        {product.name}
                      </Typography>
                      <Typography
                        variant="body2"
                        color="text.secondary"
                        sx={{
                          mb: 2,
                          flexGrow: 1,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          display: '-webkit-box',
                          WebkitLineClamp: 2,
                          WebkitBoxOrient: 'vertical',
                        }}
                      >
                        {product.description || 'No description available'}
                      </Typography>
                      <Box sx={{ 
                        display: 'flex', 
                        justifyContent: 'space-between', 
                        alignItems: 'center',
                        mt: 'auto',
                        pt: 2,
                        borderTop: '1px solid',
                        borderColor: 'divider',
                      }}>
                        <Box>
                          <Typography variant="h5" color="primary" fontWeight={700} sx={{ lineHeight: 1.2 }}>
                            ${(product.price_cents / 100).toFixed(2)}
                          </Typography>
                          <Typography variant="caption" color="text.secondary">
                            {product.currency}
                          </Typography>
                        </Box>
                      </Box>
                    </CardContent>
                  </CardActionArea>
                </Card>
              </Grid>
            ))}
          </Grid>

              {totalPages > 1 && (
                <Box sx={{ display: 'flex', justifyContent: 'center', mt: 4 }}>
                  <Pagination
                    count={totalPages}
                    page={page}
                    onChange={handlePageChange}
                    color="primary"
                    size="large"
                    showFirstButton
                    showLastButton
                  />
                </Box>
              )}
            </>
          )}
        </Grid>
      </Grid>

      <ProductModal open={modalOpen} onClose={handleCloseModal} product={selectedProduct} />
    </Container>
  )
}

export default Home
