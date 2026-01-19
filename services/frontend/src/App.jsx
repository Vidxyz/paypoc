import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { Box, Typography } from '@mui/material'
import { useAuth0 } from './auth/Auth0Provider'
import { CartProvider } from './context/CartContext'
import Home from './pages/Home'
import Checkout from './pages/Checkout'
import Orders from './pages/Orders'
import Profile from './pages/Profile'
import LoginModal from './components/LoginModal'
import SignupModal from './components/SignupModal'
import SuccessModal from './components/SuccessModal'
import ErrorModal from './components/ErrorModal'
import Navbar from './components/Navbar'

function App() {
  const { isLoading, isAuthenticated, user, login, logout, initError, error, clearError } = useAuth0()
  const [showLoginModal, setShowLoginModal] = useState(false)
  const [showSignupModal, setShowSignupModal] = useState(false)
  const [showSuccessModal, setShowSuccessModal] = useState(false)
  const [successMessage, setSuccessMessage] = useState('')

  // Extract user_id from JWT claims (custom claim added by Auth0 Action)
  // The user_id will be available in user object if added as a custom claim
  const userId = user?.['https://buyit.local/user_id'] || user?.user_id || user?.sub
  
  // Extract email for display in navbar
  const userEmail = user?.email || ''

  useEffect(() => {
    // Show login modal if not authenticated and not loading
    if (!isLoading && !isAuthenticated && !showLoginModal && !showSignupModal) {
      setShowLoginModal(true)
    }
  }, [isLoading, isAuthenticated, showLoginModal, showSignupModal])

  const handleLogin = async () => {
    try {
      await login()
    } catch (error) {
      console.error('Login error:', error)
    }
  }

  const handleSignupSuccess = (user) => {
    // Close signup modal
    setShowSignupModal(false)
    // Show success modal
    setSuccessMessage('Account created successfully! Please log in with your credentials.')
    setShowSuccessModal(true)
  }

  const handleSuccessModalClose = () => {
    setShowSuccessModal(false)
    // Redirect to login after closing the modal
    handleLogin()
  }

  const handleLogout = () => {
    logout()
  }

  // Listen for signup modal trigger
  useEffect(() => {
    const handleShowSignup = () => {
      setShowLoginModal(false)
      setShowSignupModal(true)
    }
    window.addEventListener('showSignup', handleShowSignup)
    return () => window.removeEventListener('showSignup', handleShowSignup)
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
    <CartProvider>
      <BrowserRouter>
        <Box sx={{ minHeight: '100vh', backgroundColor: 'background.default' }}>
          {isAuthenticated && (
            <Navbar onLogout={handleLogout} buyerId={userId} userEmail={userEmail} />
          )}
        
        <LoginModal
          isOpen={showLoginModal && !isAuthenticated && !showSignupModal}
          onClose={() => setShowLoginModal(false)}
          onLogin={handleLogin}
        />
        
        <SignupModal
          isOpen={showSignupModal && !isAuthenticated}
          onClose={() => {
            setShowSignupModal(false)
            setShowLoginModal(true)
          }}
          onSignupSuccess={handleSignupSuccess}
        />

        <SuccessModal
          open={showSuccessModal}
          onClose={handleSuccessModalClose}
          title="Account Created"
          message={successMessage}
          buttonText="Continue to Login"
        />

        <ErrorModal
          open={!!error}
          onClose={clearError}
          title="Authentication Error"
          message={error || ''}
          buttonText="OK"
        />

        <Routes>
          <Route
            path="/"
            element={
              isAuthenticated ? <Home /> : <Navigate to="/" replace />
            }
          />
          <Route
            path="/checkout"
            element={
              isAuthenticated ? (
                <Checkout buyerId={userId} />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/orders"
            element={
              isAuthenticated ? (
                <Orders buyerId={userId} userEmail={userEmail} />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/profile"
            element={
              isAuthenticated ? (
                <Profile />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
        </Routes>
        </Box>
      </BrowserRouter>
    </CartProvider>
  )
}

export default App
