import { useState, useEffect } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  Button,
  Typography,
  Box,
  Link,
} from '@mui/material'
import LockIcon from '@mui/icons-material/Lock'

function LoginModal({ isOpen, onClose, onLogin }) {
  const [error, setError] = useState(null)

  useEffect(() => {
    // Clear error when modal opens
    if (isOpen) {
      setError(null)
    }
  }, [isOpen])

  const handleLoginClick = async () => {
    setError(null)
    // Call login handler (redirects to Auth0)
    if (onLogin) {
      try {
        await onLogin()
      } catch (error) {
        console.error('Login error:', error)
        setError(error.message || 'Login failed. Please check the console for details.')
      }
    }
  }

  return (
    <Dialog
      open={isOpen}
      onClose={() => {}}
      maxWidth="sm"
      fullWidth
      disableEscapeKeyDown
    >
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1, pb: 1 }}>
        <LockIcon color="primary" />
        <Typography variant="h5" component="span">
          Welcome to BuyIt
        </Typography>
      </DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Please log in to continue
        </Typography>
        
        {error && (
          <Box sx={{ mb: 2, p: 2, bgcolor: 'error.light', color: 'error.contrastText', borderRadius: 1 }}>
            <Typography variant="body2">{error}</Typography>
          </Box>
        )}
        
        <Box>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2, textAlign: 'center' }}>
            You will be redirected to our secure login page
          </Typography>
          
          <Button
            onClick={handleLoginClick}
            fullWidth
            variant="contained"
            size="large"
            sx={{ mt: 3, mb: 2 }}
          >
            Log In
          </Button>
        </Box>
        
        <Box sx={{ mt: 2, textAlign: 'center' }}>
          <Typography variant="body2" color="text.secondary">
            Don't have an account?{' '}
            <Link
              component="button"
              variant="body2"
              onClick={() => {
                if (onClose) onClose()
                // Trigger signup modal - handled by parent
                window.dispatchEvent(new CustomEvent('showSignup'))
              }}
              sx={{ cursor: 'pointer', fontWeight: 'medium' }}
            >
              Sign Up
            </Link>
          </Typography>
        </Box>
      </DialogContent>
    </Dialog>
  )
}

export default LoginModal

