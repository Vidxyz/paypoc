import { useState, useEffect } from 'react'
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
} from '@mui/material'
import { catalogApiClient } from '../api/catalogApi'
import ProductModal from '../components/ProductModal'

function Home() {
  const [products, setProducts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [page, setPage] = useState(1)
  const [totalPages, setTotalPages] = useState(1)
  const [totalProducts, setTotalProducts] = useState(0)
  const [selectedProduct, setSelectedProduct] = useState(null)
  const [modalOpen, setModalOpen] = useState(false)
  const pageSize = 20

  useEffect(() => {
    fetchProducts(page)
  }, [page])

  const fetchProducts = async (currentPage) => {
    setLoading(true)
    setError(null)
    try {
      const response = await catalogApiClient.browseProducts({
        page: currentPage,
        page_size: pageSize,
      })
      setProducts(response.products || [])
      setTotalProducts(response.total || 0)
      // Calculate total pages
      const pages = Math.ceil((response.total || 0) / pageSize)
      setTotalPages(pages > 0 ? pages : 1)
    } catch (err) {
      console.error('Error fetching products:', err)
      setError(err.message || 'Failed to load products')
    } finally {
      setLoading(false)
    }
  }

  const handleProductClick = async (productId) => {
    try {
      const product = await catalogApiClient.getProduct(productId)
      setSelectedProduct(product)
      setModalOpen(true)
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

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Box sx={{ mb: 4 }}>
        <Typography variant="h3" component="h1" gutterBottom fontWeight="bold">
          Shop Products
        </Typography>
        <Typography variant="body1" color="text.secondary">
          Browse our collection of {totalProducts} products
        </Typography>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 3 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '400px' }}>
          <CircularProgress />
        </Box>
      ) : products.length === 0 ? (
        <Box sx={{ textAlign: 'center', py: 8 }}>
          <Typography variant="h5" color="text.secondary" gutterBottom>
            No products available
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Check back later for new products
          </Typography>
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
                    transition: 'transform 0.2s, box-shadow 0.2s',
                    '&:hover': {
                      transform: 'translateY(-4px)',
                      boxShadow: 4,
                    },
                  }}
                >
                  <CardActionArea
                    onClick={() => handleProductClick(product.id)}
                    sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}
                  >
                    <CardMedia
                      component="img"
                      height="200"
                      image={product.images?.[0] || 'https://via.placeholder.com/300?text=No+Image'}
                      alt={product.name}
                      sx={{
                        objectFit: 'cover',
                        backgroundColor: 'grey.100',
                      }}
                      onError={(e) => {
                        e.target.src = 'https://via.placeholder.com/300?text=Image+Not+Available'
                      }}
                    />
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
                      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <Typography variant="h6" color="primary" fontWeight="bold">
                          ${(product.price_cents / 100).toFixed(2)}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {product.currency}
                        </Typography>
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

      <ProductModal open={modalOpen} onClose={handleCloseModal} product={selectedProduct} />
    </Container>
  )
}

export default Home
