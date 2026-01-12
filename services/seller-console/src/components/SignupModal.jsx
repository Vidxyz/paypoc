import { useState } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  TextField,
  Button,
  Alert,
  Typography,
  Box,
  Link,
} from '@mui/material'
import PersonAddIcon from '@mui/icons-material/PersonAdd'
import userApiClient from '../api/userApi'

function SignupModal({ isOpen, onClose, onSignupSuccess }) {
  const [formData, setFormData] = useState({
    email: '',
    password: '',
    confirmPassword: '',
    firstname: '',
    lastname: '',
  })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleChange = (e) => {
    setFormData({
      ...formData,
      [e.target.name]: e.target.value,
    })
    if (error) setError('')
  }

  const validateForm = () => {
    if (!formData.email || !formData.password || !formData.firstname || !formData.lastname) {
      setError('All fields are required')
      return false
    }

    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email)) {
      setError('Please enter a valid email address')
      return false
    }

    if (formData.password.length < 8) {
      setError('Password must be at least 8 characters long')
      return false
    }

    if (formData.password !== formData.confirmPassword) {
      setError('Passwords do not match')
      return false
    }

    return true
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')

    if (!validateForm()) {
      return
    }

    setLoading(true)

    try {
      // Call seller signup endpoint (creates SELLER account)
      const user = await userApiClient.sellerSignup({
        email: formData.email,
        password: formData.password,
        firstname: formData.firstname,
        lastname: formData.lastname,
      })

      if (onSignupSuccess) {
        onSignupSuccess(user)
      }
    } catch (err) {
      setError(err.response?.data?.error || err.message || 'Failed to create seller account. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Dialog
      open={isOpen}
      onClose={onClose}
      maxWidth="sm"
      fullWidth
    >
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1, pb: 1 }}>
        <PersonAddIcon color="primary" />
        <Typography variant="h5" component="span">
          Create Seller Account
        </Typography>
      </DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Sign up for a SELLER account to access the seller console
        </Typography>
        
        <form onSubmit={handleSubmit}>
          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}
          
          <TextField
            fullWidth
            label="First Name"
            name="firstname"
            value={formData.firstname}
            onChange={handleChange}
            margin="normal"
            required
            autoFocus
          />
          
          <TextField
            fullWidth
            label="Last Name"
            name="lastname"
            value={formData.lastname}
            onChange={handleChange}
            margin="normal"
            required
          />
          
          <TextField
            fullWidth
            label="Email"
            name="email"
            type="email"
            value={formData.email}
            onChange={handleChange}
            margin="normal"
            required
          />
          
          <TextField
            fullWidth
            label="Password"
            name="password"
            type="password"
            value={formData.password}
            onChange={handleChange}
            margin="normal"
            required
            helperText="Must be at least 8 characters"
          />
          
          <TextField
            fullWidth
            label="Confirm Password"
            name="confirmPassword"
            type="password"
            value={formData.confirmPassword}
            onChange={handleChange}
            margin="normal"
            required
          />
          
          <Button
            type="submit"
            fullWidth
            variant="contained"
            size="large"
            disabled={loading}
            sx={{ mt: 3, mb: 2 }}
          >
            {loading ? 'Creating Seller Account...' : 'Create Seller Account'}
          </Button>
        </form>
        
        <Box sx={{ mt: 2, textAlign: 'center' }}>
          <Typography variant="body2" color="text.secondary">
            Already have an account?{' '}
            <Link
              component="button"
              variant="body2"
              onClick={onClose}
              sx={{ cursor: 'pointer' }}
            >
              Log In
            </Link>
          </Typography>
        </Box>
      </DialogContent>
    </Dialog>
  )
}

export default SignupModal

