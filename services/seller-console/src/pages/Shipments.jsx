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
  TextField,
  MenuItem,
  IconButton,
  Divider,
  Grid,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import ErrorIcon from '@mui/icons-material/Error'
import WarningIcon from '@mui/icons-material/Warning'
import InfoIcon from '@mui/icons-material/Info'
import LocalShippingIcon from '@mui/icons-material/LocalShipping'
import CloseIcon from '@mui/icons-material/Close'
import EditIcon from '@mui/icons-material/Edit'
import { createFulfillmentApiClient } from '../api/fulfillmentApi'
import { useAuth0 } from '../auth/Auth0Provider'

const SHIPMENT_STATUSES = [
  'PENDING',
  'PROCESSING',
  'SHIPPED',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'CANCELLED',
  'RETURNED',
]

const CARRIERS = ['UPS', 'FEDEX', 'DHL', 'USPS', 'CANADA_POST', 'OTHER']

function Shipments({ userEmail }) {
  const { getAccessToken } = useAuth0()
  const [shipments, setShipments] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedShipment, setSelectedShipment] = useState(null)
  const [editDialogOpen, setEditDialogOpen] = useState(false)
  const [editType, setEditType] = useState(null) // 'status' or 'tracking'
  const [editStatus, setEditStatus] = useState('')
  const [editTrackingNumber, setEditTrackingNumber] = useState('')
  const [editCarrier, setEditCarrier] = useState('')
  const [saving, setSaving] = useState(false)
  const [fulfillmentApiClient, setFulfillmentApiClient] = useState(null)

  useEffect(() => {
    const client = createFulfillmentApiClient(getAccessToken)
    setFulfillmentApiClient(() => client)
    
    return () => {
      if (client.cleanup) client.cleanup()
    }
  }, [getAccessToken])

  useEffect(() => {
    if (fulfillmentApiClient) {
      loadShipments()
    }
  }, [fulfillmentApiClient])

  const loadShipments = async () => {
    if (!fulfillmentApiClient) return
    
    setLoading(true)
    setError('')
    
    try {
      const data = await fulfillmentApiClient.getShipments({ limit: 100 })
      setShipments(Array.isArray(data) ? data : [])
    } catch (err) {
      if (err.response?.status === 401) {
        setError('Unauthorized. Please log in again.')
      } else {
        setError(err.message || 'Failed to load shipments')
      }
    } finally {
      setLoading(false)
    }
  }

  const handleEditStatus = (shipment) => {
    setSelectedShipment(shipment)
    setEditType('status')
    setEditStatus(shipment.status)
    setEditDialogOpen(true)
  }

  const handleEditTracking = (shipment) => {
    setSelectedShipment(shipment)
    setEditType('tracking')
    setEditTrackingNumber(shipment.trackingNumber || shipment.tracking_number || '')
    setEditCarrier(shipment.carrier || '')
    setEditDialogOpen(true)
  }

  const handleSave = async () => {
    if (!fulfillmentApiClient || !selectedShipment) return
    
    setSaving(true)
    setError('')
    
    try {
      if (editType === 'status') {
        await fulfillmentApiClient.updateShipmentStatus(selectedShipment.id, editStatus)
      } else if (editType === 'tracking') {
        if (!editTrackingNumber || !editCarrier) {
          setError('Tracking number and carrier are required')
          setSaving(false)
          return
        }
        await fulfillmentApiClient.addTracking(selectedShipment.id, editTrackingNumber, editCarrier)
      }
      
      // Reload shipments
      await loadShipments()
      setEditDialogOpen(false)
      setSelectedShipment(null)
      setEditType(null)
      setEditStatus('')
      setEditTrackingNumber('')
      setEditCarrier('')
    } catch (err) {
      setError(err.message || 'Failed to update shipment')
    } finally {
      setSaving(false)
    }
  }

  const handleCloseEditDialog = () => {
    setEditDialogOpen(false)
    setSelectedShipment(null)
    setEditType(null)
    setEditStatus('')
    setEditTrackingNumber('')
    setEditCarrier('')
  }

  const getStatusChip = (status) => {
    const chips = {
      PENDING: { label: status, color: 'warning', icon: <WarningIcon /> },
      PROCESSING: { label: status, color: 'info', icon: <InfoIcon /> },
      SHIPPED: { label: status, color: 'info', icon: <LocalShippingIcon /> },
      IN_TRANSIT: { label: status, color: 'info', icon: <LocalShippingIcon /> },
      OUT_FOR_DELIVERY: { label: status, color: 'info', icon: <LocalShippingIcon /> },
      DELIVERED: { label: status, color: 'success', icon: <CheckCircleIcon /> },
      CANCELLED: { label: status, color: 'error', icon: <ErrorIcon /> },
      RETURNED: { label: status, color: 'error', icon: <ErrorIcon /> },
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

  if (loading && shipments.length === 0) {
    return (
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Card>
          <CardContent sx={{ textAlign: 'center', py: 8 }}>
            <CircularProgress />
            <Typography variant="body1" color="text.secondary" sx={{ mt: 2 }}>
              Loading shipments...
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
                Shipments
              </Typography>
              <Typography variant="body1" color="text.secondary">
                Manage your shipments and tracking information
              </Typography>
            </Box>
            <Button
              onClick={loadShipments}
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

          {shipments.length === 0 ? (
            <Box sx={{ textAlign: 'center', py: 8 }}>
              <LocalShippingIcon sx={{ fontSize: 64, color: 'text.secondary', mb: 2 }} />
              <Typography variant="h6" color="text.secondary" gutterBottom>
                No shipments found
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Shipments will appear here once orders are placed.
              </Typography>
            </Box>
          ) : (
            <TableContainer component={Paper} variant="outlined">
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell><strong>Shipment ID</strong></TableCell>
                    <TableCell><strong>Order ID</strong></TableCell>
                    <TableCell><strong>Status</strong></TableCell>
                    <TableCell><strong>Tracking</strong></TableCell>
                    <TableCell><strong>Carrier</strong></TableCell>
                    <TableCell><strong>Shipped</strong></TableCell>
                    <TableCell><strong>Delivered</strong></TableCell>
                    <TableCell><strong>Actions</strong></TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {shipments.map((shipment) => (
                    <TableRow key={shipment.id} hover>
                      <TableCell>
                        <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                          {shipment.id?.substring(0, 8)}...
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" component="code" sx={{ fontFamily: 'monospace' }}>
                          {shipment.orderId?.substring(0, 8) || shipment.order_id?.substring(0, 8)}...
                        </Typography>
                      </TableCell>
                      <TableCell>
                        {getStatusChip(shipment.status)}
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {shipment.trackingNumber || shipment.tracking_number || 'N/A'}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {shipment.carrier || 'N/A'}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {formatDate(shipment.shippedAt || shipment.shipped_at)}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {formatDate(shipment.deliveredAt || shipment.delivered_at)}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Box sx={{ display: 'flex', gap: 1 }}>
                          <Button
                            size="small"
                            variant="outlined"
                            startIcon={<EditIcon />}
                            onClick={() => handleEditStatus(shipment)}
                          >
                            Status
                          </Button>
                          <Button
                            size="small"
                            variant="outlined"
                            startIcon={<EditIcon />}
                            onClick={() => handleEditTracking(shipment)}
                          >
                            Tracking
                          </Button>
                        </Box>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* Edit Dialog */}
      <Dialog
        open={editDialogOpen}
        onClose={handleCloseEditDialog}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <EditIcon color="primary" />
            <Typography variant="h6">
              {editType === 'status' ? 'Update Shipment Status' : 'Add/Update Tracking Information'}
            </Typography>
          </Box>
          <IconButton
            onClick={handleCloseEditDialog}
            size="small"
            disabled={saving}
          >
            <CloseIcon />
          </IconButton>
        </DialogTitle>
        <DialogContent>
          {editType === 'status' ? (
            <Box sx={{ pt: 2 }}>
              <TextField
                fullWidth
                select
                label="Status"
                value={editStatus}
                onChange={(e) => setEditStatus(e.target.value)}
                sx={{ mb: 2 }}
              >
                {SHIPMENT_STATUSES.map((status) => (
                  <MenuItem key={status} value={status}>
                    {status}
                  </MenuItem>
                ))}
              </TextField>
              <Alert severity="info" sx={{ mt: 2 }}>
                Valid status transitions will be enforced by the backend. Invalid transitions will be rejected.
              </Alert>
            </Box>
          ) : (
            <Box sx={{ pt: 2 }}>
              <TextField
                fullWidth
                label="Tracking Number"
                value={editTrackingNumber}
                onChange={(e) => setEditTrackingNumber(e.target.value)}
                sx={{ mb: 2 }}
                required
              />
              <TextField
                fullWidth
                select
                label="Carrier"
                value={editCarrier}
                onChange={(e) => setEditCarrier(e.target.value)}
                required
              >
                {CARRIERS.map((carrier) => (
                  <MenuItem key={carrier} value={carrier}>
                    {carrier}
                  </MenuItem>
                ))}
              </TextField>
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button
            onClick={handleCloseEditDialog}
            disabled={saving}
          >
            Cancel
          </Button>
          <Button
            onClick={handleSave}
            variant="contained"
            disabled={saving || (editType === 'tracking' && (!editTrackingNumber || !editCarrier))}
          >
            {saving ? <CircularProgress size={20} /> : 'Save'}
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  )
}

export default Shipments
