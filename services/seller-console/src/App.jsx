import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { Box, Typography } from '@mui/material'
import { useAuth0 } from './auth/Auth0Provider'
import SellerLanding from './pages/SellerLanding'
import SellerProfile from './pages/SellerProfile'
import SellerNavbar from './components/SellerNavbar'
import LoginModal from './components/LoginModal'
import SignupModal from './components/SignupModal'
import SuccessModal from './components/SuccessModal'
import ErrorModal from './components/ErrorModal'

function App() {
  const { isLoading, isAuthenticated, user, login, logout, initError, error, setError, clearError } = useAuth0()
  const [showLoginModal, setShowLoginModal] = useState(false)
  const [showSignupModal, setShowSignupModal] = useState(false)
  const [showSuccessModal, setShowSuccessModal] = useState(false)
  const [successMessage, setSuccessMessage] = useState('')

  // Extract account_type from JWT claims to verify SELLER
  const accountType = user?.['https://buyit.local/account_type'] || user?.account_type
  const userEmail = user?.email || user?.['https://buyit.local/email'] || ''

  // Check if user is authenticated AND is a SELLER
  const isSellerAuthenticated = isAuthenticated && accountType === 'SELLER'

  useEffect(() => {
    // Show login modal if not authenticated and not loading
    if (!isLoading && !isSellerAuthenticated && !showLoginModal && !showSignupModal) {
      setShowLoginModal(true)
    }
  }, [isLoading, isSellerAuthenticated, showLoginModal, showSignupModal])

  const handleLogin = async () => {
    try {
      await login()
    } catch (error) {
      console.error('Login error:', error)
    }
  }

  const handleSignupSuccess = (user) => {
    setShowSignupModal(false)
    setSuccessMessage('Seller account created successfully! Please log in with your credentials.')
    setShowSuccessModal(true)
  }

  const handleSuccessModalClose = () => {
    setShowSuccessModal(false)
    handleLogin()
  }

  const handleLogout = () => {
    logout()
    setShowLoginModal(false)
  }

  // Listen for seller signup modal trigger
  useEffect(() => {
    const handleShowSellerSignup = () => {
      setShowLoginModal(false)
      setShowSignupModal(true)
    }
    window.addEventListener('showSellerSignup', handleShowSellerSignup)
    return () => window.removeEventListener('showSellerSignup', handleShowSellerSignup)
  }, [])

  // Show loading state
  if (isLoading) {
    return (
      <Box sx={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 2 }}>
        <Box>Loading...</Box>
        {initError && (
          <Box sx={{ p: 2, bgcolor: 'error.light', color: 'error.contrastText', borderRadius: 1, maxWidth: 600 }}>
            <Typography variant="body2">Auth0 Error: {initError}</Typography>
          </Box>
        )}
      </Box>
    )
  }

  return (
    <BrowserRouter>
      <Box sx={{ minHeight: '100vh', backgroundColor: 'background.default' }}>
        {isSellerAuthenticated && (
          <SellerNavbar onLogout={handleLogout} userEmail={userEmail} />
        )}
        
        <LoginModal
          isOpen={showLoginModal && !isSellerAuthenticated && !showSignupModal && !error}
          onClose={() => setShowLoginModal(false)}
          onLogin={handleLogin}
        />
        
        <SignupModal
          isOpen={showSignupModal && !isSellerAuthenticated && !error}
          onClose={() => {
            setShowSignupModal(false)
            setShowLoginModal(true)
          }}
          onSignupSuccess={handleSignupSuccess}
        />

        <SuccessModal
          open={showSuccessModal}
          onClose={handleSuccessModalClose}
          title="Seller Account Created"
          message={successMessage}
          buttonText="Continue to Login"
        />

        <ErrorModal
          open={!!error}
          onClose={() => {
            clearError()
            // Show login modal again after error is dismissed
            if (!isSellerAuthenticated) {
              setShowLoginModal(true)
            }
          }}
          title="Authentication Error"
          message={error || ''}
          buttonText="OK"
        />

        <Routes>
          <Route
            path="/"
            element={
              isSellerAuthenticated ? (
                <SellerLanding />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/dashboard"
            element={
              isSellerAuthenticated ? (
                <SellerLanding />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/profile"
            element={
              isSellerAuthenticated ? (
                <SellerProfile />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
        </Routes>
      </Box>
    </BrowserRouter>
  )
}

export default App

