import { useState } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Button,
  Alert,
  Box,
  Typography,
} from '@mui/material'
import { CreditCard } from '@mui/icons-material'

function AddStripeAccountModal({ open, onClose, onSuccess, currency = 'CAD' }) {
  const [stripeAccountId, setStripeAccountId] = useState('')
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  const handleClose = () => {
    setStripeAccountId('')
    setError(null)
    onClose()
  }

  const handleSubmit = async () => {
    // Validate Stripe account ID format
    if (!stripeAccountId.trim()) {
      setError('Stripe account ID is required')
      return
    }

    if (!stripeAccountId.startsWith('acct_')) {
      setError('Stripe account ID must start with "acct_"')
      return
    }

    try {
      setLoading(true)
      setError(null)
      await onSuccess(stripeAccountId, currency)
      handleClose()
    } catch (err) {
      setError(err.message || 'Failed to update Stripe account ID')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <CreditCard />
          <Typography variant="h6">Add Stripe Account</Typography>
        </Box>
      </DialogTitle>
      <DialogContent>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          
          <Alert severity="info">
            Enter your Stripe connected account ID. This is the account ID from your Stripe dashboard
            (e.g., acct_1234567890).
          </Alert>

          <TextField
            label="Stripe Account ID"
            value={stripeAccountId}
            onChange={(e) => setStripeAccountId(e.target.value)}
            placeholder="acct_1234567890"
            fullWidth
            required
            error={!!error && !stripeAccountId.startsWith('acct_')}
            helperText={
              error && !stripeAccountId.startsWith('acct_')
                ? 'Stripe account ID must start with "acct_"'
                : 'Your Stripe connected account ID (starts with "acct_")'
            }
            disabled={loading}
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={loading}>
          Cancel
        </Button>
        <Button
          onClick={handleSubmit}
          variant="contained"
          disabled={loading || !stripeAccountId.trim()}
        >
          {loading ? 'Saving...' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

export default AddStripeAccountModal

