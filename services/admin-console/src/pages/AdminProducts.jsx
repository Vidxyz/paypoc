import { useState } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Button,
  CircularProgress,
  Alert,
  Snackbar,
  Box,
} from '@mui/material'
import SyncIcon from '@mui/icons-material/Sync'
import { syncInventory } from '../api/adminApi'

function AdminProducts() {
  const [loading, setLoading] = useState(false)
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' })
  const [syncResult, setSyncResult] = useState(null)

  const handleSyncInventory = async () => {
    setLoading(true)
    setSyncResult(null)
    
    try {
      const result = await syncInventory()
      setSyncResult(result)
      setSnackbar({
        open: true,
        message: `Inventory sync completed successfully! Synced ${result.synced_count} out of ${result.total_products} products.`,
        severity: 'success'
      })
    } catch (error) {
      const errorMessage = error.response?.data?.detail || error.message || 'Failed to sync inventory'
      setSnackbar({
        open: true,
        message: errorMessage,
        severity: 'error'
      })
    } finally {
      setLoading(false)
    }
  }

  const handleCloseSnackbar = () => {
    setSnackbar({ ...snackbar, open: false })
  }

  return (
    <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
      <Card>
        <CardContent>
          <Typography variant="h4" component="h1" gutterBottom>
            Products Management
          </Typography>
          
          <Typography variant="body1" color="text.secondary" sx={{ mb: 4 }}>
            Manage product inventory synchronization between the catalog and inventory services.
          </Typography>

          <Box sx={{ mb: 3 }}>
            <Button
              variant="contained"
              color="primary"
              startIcon={loading ? <CircularProgress size={20} color="inherit" /> : <SyncIcon />}
              onClick={handleSyncInventory}
              disabled={loading}
              sx={{ minWidth: 200 }}
            >
              {loading ? 'Syncing...' : 'Sync Inventory'}
            </Button>
          </Box>

          {syncResult && (
            <Alert severity="success" sx={{ mt: 2 }}>
              <Typography variant="body2" component="div">
                <strong>Sync completed successfully!</strong>
              </Typography>
              <Typography variant="body2" component="div" sx={{ mt: 1 }}>
                • Total products processed: {syncResult.total_products}
              </Typography>
              <Typography variant="body2" component="div">
                • Products synced: {syncResult.synced_count}
              </Typography>
              {syncResult.products_without_inventory > 0 && (
                <Typography variant="body2" component="div">
                  • Products without inventory: {syncResult.products_without_inventory}
                </Typography>
              )}
            </Alert>
          )}

          <Box sx={{ mt: 4 }}>
            <Typography variant="h6" gutterBottom>
              About Inventory Sync
            </Typography>
            <Typography variant="body2" color="text.secondary" paragraph>
              The inventory sync operation fetches all active products from the catalog service
              and synchronizes their inventory data from the inventory service into the catalog's
              denormalized product_inventory table. This ensures efficient database-level sorting
              and filtering by availability.
            </Typography>
            <Typography variant="body2" color="text.secondary">
              This operation can be run whenever you need to refresh the inventory cache, such as
              after bulk inventory updates or when the cache becomes out of sync.
            </Typography>
          </Box>
        </CardContent>
      </Card>

      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={handleCloseSnackbar}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert onClose={handleCloseSnackbar} severity={snackbar.severity} sx={{ width: '100%' }}>
          {snackbar.message}
        </Alert>
      </Snackbar>
    </Container>
  )
}

export default AdminProducts
