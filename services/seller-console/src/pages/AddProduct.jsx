import { useState, useEffect, useMemo } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Box,
  Button,
  TextField,
  Grid,
  Alert,
  CircularProgress,
  MenuItem,
  Tabs,
  Tab,
  Paper,
  IconButton,
  Chip,
} from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import CloudUploadIcon from '@mui/icons-material/CloudUpload'
import LinkIcon from '@mui/icons-material/Link'
import DeleteIcon from '@mui/icons-material/Delete'
import { useAuth0 } from '../auth/Auth0Provider'
import { createCatalogApiClient } from '../api/catalogApi'
import { useNavigate, useParams } from 'react-router-dom'

function AddProduct() {
  const { isAuthenticated, getAccessToken } = useAuth0()
  const navigate = useNavigate()
  const { productId } = useParams()
  const isEditMode = !!productId
  
  // Form state
  const [sku, setSku] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [priceCents, setPriceCents] = useState('')
  const [currency, setCurrency] = useState('USD')
  const [status, setStatus] = useState('DRAFT')
  const [images, setImages] = useState([]) // Array of { public_id, url, type: 'file' | 'url' }
  
  // UI state
  const [uploadTab, setUploadTab] = useState(0) // 0 = file upload, 1 = URL
  const [imageUrl, setImageUrl] = useState('')
  const [uploading, setUploading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)
  const [categories, setCategories] = useState([])
  const [selectedCategoryId, setSelectedCategoryId] = useState('')
  const [productCategoryId, setProductCategoryId] = useState(null) // Store category ID from product
  
  // Organize categories for display: group subcategories under their parents
  const organizedCategories = useMemo(() => {
    if (!categories.length) return []
    
    const topLevel = []
    const processedSubcategories = new Set()
    
    // Build hierarchy: top-level categories first, then their subcategories
    categories.forEach(cat => {
      // Check if it's a top-level category (no parent_id or parent_id is null/undefined)
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
    
    // Add any remaining subcategories that weren't processed (shouldn't happen, but safety check)
    categories.forEach(cat => {
      if (cat.parent_id && !processedSubcategories.has(String(cat.id))) {
        topLevel.push(cat)
      }
    })
    
    return topLevel
  }, [categories])

  // Load categories on mount
  useEffect(() => {
    if (!isAuthenticated) return
    
    const loadCategories = async () => {
      try {
        const catalogApi = createCatalogApiClient(getAccessToken)
        const categoriesData = await catalogApi.getCategories()
        setCategories(categoriesData)
        catalogApi.cleanup()
      } catch (err) {
        console.warn('Failed to load categories:', err)
      }
    }
    loadCategories()
  }, [isAuthenticated, getAccessToken])
  
  // Sync selected category when both categories and productCategoryId are available
  useEffect(() => {
    if (productCategoryId && categories.length > 0) {
      // Verify the category exists in the loaded categories
      const categoryExists = categories.some(cat => String(cat.id) === productCategoryId)
      if (categoryExists && selectedCategoryId !== productCategoryId) {
        setSelectedCategoryId(productCategoryId)
      }
    }
  }, [categories, productCategoryId, selectedCategoryId])

  // Load product data if in edit mode
  useEffect(() => {
    if (!isAuthenticated || !isEditMode || !productId) return

    const loadProduct = async () => {
      try {
        const catalogApi = createCatalogApiClient(getAccessToken)
        const product = await catalogApi.getProduct(productId)
        
        // Populate form with product data
        setSku(product.sku || '')
        setName(product.name || '')
        setDescription(product.description || '')
        setPriceCents(product.price_cents ? (product.price_cents / 100).toString() : '')
        setCurrency(product.currency || 'USD')
        setStatus(product.status || 'DRAFT')
        // Store category_id to set after categories are loaded
        if (product.category_id) {
          setProductCategoryId(String(product.category_id))
        }
        
        // Load images - API now returns URLs directly
        if (product.images && product.images.length > 0) {
          // Extract public_id from Cloudinary URL
          // URL format: https://res.cloudinary.com/{cloud_name}/image/upload/{version}/{public_id}
          const loadedImages = product.images.map((imageUrl) => {
            try {
              // Extract public_id from URL
              // Pattern: https://res.cloudinary.com/{cloud}/image/upload/{version}/{public_id}
              const urlMatch = imageUrl.match(/\/upload\/(?:v\d+\/)?(.+)$/)
              const publicId = urlMatch ? urlMatch[1] : null
              
              if (publicId) {
                return { public_id: publicId, url: imageUrl, type: 'file' }
              } else {
                console.warn(`Could not extract public_id from URL: ${imageUrl}`)
                return null
              }
            } catch (err) {
              console.warn(`Failed to process image URL ${imageUrl}:`, err)
              return null
            }
          }).filter(img => img !== null)
          
          setImages(loadedImages)
        }
        
        catalogApi.cleanup()
      } catch (err) {
        console.error('Failed to load product:', err)
        setError(`Failed to load product: ${err.message}`)
      }
    }
    
    loadProduct()
  }, [isAuthenticated, isEditMode, productId, getAccessToken])

  const handleFileUpload = async (event) => {
    const file = event.target.files?.[0]
    if (!file) return

    // Validate file type
    if (!file.type.startsWith('image/')) {
      setError('File must be an image')
      return
    }

    try {
      setUploading(true)
      setError(null)

      const catalogApi = createCatalogApiClient(getAccessToken)
      const publicId = await catalogApi.uploadImageFile(file)
      
      // Get the URL for display
      const url = await catalogApi.getImageUrl(publicId)
      
      setImages([...images, { public_id: publicId, url, type: 'file' }])
      catalogApi.cleanup()
    } catch (err) {
      console.error('Failed to upload image:', err)
      setError(`Failed to upload image: ${err.message}`)
    } finally {
      setUploading(false)
      // Reset file input
      event.target.value = ''
    }
  }

  const handleUrlUpload = async () => {
    if (!imageUrl.trim()) {
      setError('Please enter an image URL')
      return
    }

    try {
      setUploading(true)
      setError(null)

      const catalogApi = createCatalogApiClient(getAccessToken)
      const publicId = await catalogApi.uploadImageUrl(imageUrl)
      
      // Get the URL for display
      const url = await catalogApi.getImageUrl(publicId)
      
      setImages([...images, { public_id: publicId, url, type: 'url' }])
      setImageUrl('')
      catalogApi.cleanup()
    } catch (err) {
      console.error('Failed to upload image from URL:', err)
      setError(`Failed to upload image: ${err.message}`)
    } finally {
      setUploading(false)
    }
  }

  const handleRemoveImage = (index) => {
    setImages(images.filter((_, i) => i !== index))
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    
    // Validation
    if (!sku.trim()) {
      setError('SKU is required')
      return
    }
    if (!name.trim()) {
      setError('Product name is required')
      return
    }
    if (!priceCents || parseFloat(priceCents) <= 0) {
      setError('Price must be greater than 0')
      return
    }

    try {
      setSubmitting(true)
      setError(null)

      const catalogApi = createCatalogApiClient(getAccessToken)
      
      const productData = {
        sku: sku.trim(),
        name: name.trim(),
        description: description.trim() || null,
        category_id: selectedCategoryId && selectedCategoryId !== '' ? selectedCategoryId : null,
        price_cents: Math.round(parseFloat(priceCents) * 100), // Convert to cents
        currency: currency,
        status: status,
        images: images.map(img => img.public_id),
      }

      if (isEditMode) {
        await catalogApi.updateProduct(productId, productData)
      } else {
        await catalogApi.createProduct(productData)
      }
      catalogApi.cleanup()
      
      // Navigate back to products list
      navigate('/products')
    } catch (err) {
      console.error(`Failed to ${isEditMode ? 'update' : 'create'} product:`, err)
      setError(`Failed to ${isEditMode ? 'update' : 'create'} product: ${err.message}`)
    } finally {
      setSubmitting(false)
    }
  }

  const formatPrice = (cents) => {
    return (cents / 100).toFixed(2)
  }

  return (
    <Container maxWidth="md" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 4 }}>
        <IconButton onClick={() => navigate('/products')} sx={{ mr: 2 }}>
          <ArrowBackIcon />
        </IconButton>
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
          {isEditMode ? 'Edit Product' : 'Add New Product'}
        </Typography>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 3 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <form onSubmit={handleSubmit}>
        <Grid container spacing={3}>
          {/* Basic Information */}
          <Grid item xs={12}>
            <Card>
              <CardContent>
                <Typography variant="h6" gutterBottom>
                  Basic Information
                </Typography>
                <Grid container spacing={2} sx={{ mt: 1 }}>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      label="SKU"
                      value={sku}
                      onChange={(e) => setSku(e.target.value)}
                      required
                      helperText="Unique product identifier"
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      label="Product Name"
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      required
                    />
                  </Grid>
                  <Grid item xs={12}>
                    <TextField
                      fullWidth
                      label="Description"
                      value={description}
                      onChange={(e) => setDescription(e.target.value)}
                      multiline
                      rows={4}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      select
                      label="Category"
                      value={selectedCategoryId || ''}
                      onChange={(e) => setSelectedCategoryId(e.target.value)}
                    >
                      <MenuItem value="">None</MenuItem>
                      {organizedCategories.map((category, index) => {
                        const isSubcategory = category.parent_id !== null && category.parent_id !== undefined
                        // Ensure category.id is always a string for consistent comparison
                        const categoryId = String(category.id)
                        // Check if this is the first item after a top-level category (for visual separation)
                        const prevCategory = index > 0 ? organizedCategories[index - 1] : null
                        const isFirstSubcategory = isSubcategory && prevCategory && !prevCategory.parent_id
                        
                        return (
                          <MenuItem 
                            key={categoryId} 
                            value={categoryId}
                            sx={isSubcategory ? { pl: 4 } : {}}
                          >
                            {isSubcategory ? `└─ ${category.name}` : category.name}
                          </MenuItem>
                        )
                      })}
                    </TextField>
                  </Grid>
                </Grid>
              </CardContent>
            </Card>
          </Grid>

          {/* Pricing */}
          <Grid item xs={12}>
            <Card>
              <CardContent>
                <Typography variant="h6" gutterBottom>
                  Pricing
                </Typography>
                <Grid container spacing={2} sx={{ mt: 1 }}>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      label="Price"
                      type="number"
                      value={priceCents}
                      onChange={(e) => setPriceCents(e.target.value)}
                      required
                      inputProps={{ min: 0, step: 0.01 }}
                      helperText={`${formatPrice(parseFloat(priceCents) * 100 || 0)} ${currency}`}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      select
                      label="Currency"
                      value={currency}
                      onChange={(e) => setCurrency(e.target.value)}
                      required
                    >
                      <MenuItem value="USD">USD</MenuItem>
                      <MenuItem value="CAD">CAD</MenuItem>
                      <MenuItem value="EUR">EUR</MenuItem>
                      <MenuItem value="GBP">GBP</MenuItem>
                    </TextField>
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      select
                      label="Status"
                      value={status}
                      onChange={(e) => setStatus(e.target.value)}
                      required
                    >
                      <MenuItem value="DRAFT">Draft</MenuItem>
                      <MenuItem value="ACTIVE">Active</MenuItem>
                      <MenuItem value="INACTIVE">Inactive</MenuItem>
                    </TextField>
                  </Grid>
                </Grid>
              </CardContent>
            </Card>
          </Grid>

          {/* Images */}
          <Grid item xs={12}>
            <Card>
              <CardContent>
                <Typography variant="h6" gutterBottom>
                  Product Images
                </Typography>
                
                <Tabs value={uploadTab} onChange={(e, v) => setUploadTab(v)} sx={{ mb: 2 }}>
                  <Tab icon={<CloudUploadIcon />} label="Upload File" />
                  <Tab icon={<LinkIcon />} label="From URL" />
                </Tabs>

                {uploadTab === 0 && (
                  <Box>
                    <input
                      accept="image/*"
                      style={{ display: 'none' }}
                      id="image-upload-input"
                      type="file"
                      onChange={handleFileUpload}
                      disabled={uploading}
                    />
                    <label htmlFor="image-upload-input">
                      <Button
                        variant="outlined"
                        component="span"
                        startIcon={<CloudUploadIcon />}
                        disabled={uploading}
                        fullWidth
                      >
                        {uploading ? 'Uploading...' : 'Choose Image File'}
                      </Button>
                    </label>
                  </Box>
                )}

                {uploadTab === 1 && (
                  <Box sx={{ display: 'flex', gap: 1 }}>
                    <TextField
                      fullWidth
                      label="Image URL"
                      value={imageUrl}
                      onChange={(e) => setImageUrl(e.target.value)}
                      placeholder="https://example.com/image.jpg"
                      disabled={uploading}
                    />
                    <Button
                      variant="outlined"
                      onClick={handleUrlUpload}
                      disabled={uploading || !imageUrl.trim()}
                      sx={{ minWidth: 120 }}
                    >
                      {uploading ? <CircularProgress size={24} /> : 'Upload'}
                    </Button>
                  </Box>
                )}

                {images.length > 0 && (
                  <Box sx={{ mt: 3 }}>
                    <Typography variant="subtitle2" gutterBottom>
                      Uploaded Images ({images.length})
                    </Typography>
                    <Grid container spacing={2}>
                      {images.map((img, index) => (
                        <Grid item xs={6} sm={4} md={3} key={index}>
                          <Paper
                            sx={{
                              position: 'relative',
                              padding: 1,
                              borderRadius: 1,
                            }}
                          >
                            <Box
                              component="img"
                              src={img.url}
                              alt={`Product image ${index + 1}`}
                              sx={{
                                width: '100%',
                                height: 150,
                                objectFit: 'cover',
                                borderRadius: 1,
                              }}
                            />
                            <IconButton
                              size="small"
                              onClick={() => handleRemoveImage(index)}
                              sx={{
                                position: 'absolute',
                                top: 8,
                                right: 8,
                                bgcolor: 'rgba(255, 255, 255, 0.9)',
                              }}
                            >
                              <DeleteIcon fontSize="small" />
                            </IconButton>
                            <Chip
                              label={img.type === 'file' ? 'File' : 'URL'}
                              size="small"
                              sx={{ mt: 1, width: '100%' }}
                            />
                          </Paper>
                        </Grid>
                      ))}
                    </Grid>
                  </Box>
                )}
              </CardContent>
            </Card>
          </Grid>

          {/* Actions */}
          <Grid item xs={12}>
            <Box sx={{ display: 'flex', gap: 2, justifyContent: 'flex-end' }}>
              <Button
                variant="outlined"
                onClick={() => navigate('/products')}
                disabled={submitting}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                variant="contained"
                disabled={submitting}
              >
                {submitting ? <CircularProgress size={24} /> : (isEditMode ? 'Update Product' : 'Create Product')}
              </Button>
            </Box>
          </Grid>
        </Grid>
      </form>
    </Container>
  )
}

export default AddProduct

