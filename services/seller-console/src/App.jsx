import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { Box, Typography } from '@mui/material'
import { useAuth0 } from './auth/Auth0Provider'
import SellerLanding from './pages/SellerLanding'
import SellerProfile from './pages/SellerProfile'
import Products from './pages/Products'
import AddProduct from './pages/AddProduct'
import SellerOrders from './pages/SellerOrders'
import Shipments from './pages/Shipments'
import Payouts from './pages/Payouts'
import SellerNavbar from './components/SellerNavbar'
import LoginModal from './components/LoginModal'
import SignupModal from './components/SignupModal'
import SuccessModal from './components/SuccessModal'
import ErrorModal from './components/ErrorModal'

function AppContent() {
  const { isLoading, isAuthenticated, user, login, logout, initError, error, setError, clearError } = useAuth0()
  const navigate = useNavigate()
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
    // Don't show if error modal is open (let error modal handle the flow)
    if (!isLoading && !isSellerAuthenticated && !showLoginModal && !showSignupModal && !error) {
      setShowLoginModal(true)
    }
  }, [isLoading, isSellerAuthenticated, showLoginModal, showSignupModal, error])

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

  const handleLogout = async () => {
    // Clear all local storage and cookies first
    localStorage.removeItem('bearerToken')
    localStorage.removeItem('auth0UserId')
    
    // Clear Auth0 cache
    try {
      const keysToRemove = []
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
          keysToRemove.push(key)
        }
      }
      keysToRemove.forEach(key => localStorage.removeItem(key))
    } catch (error) {
      console.error('Error clearing Auth0 cache:', error)
    }
    
    // Call Auth0 logout
    await logout()
    
    // Navigate to home to clear any protected routes
    navigate('/', { replace: true })
    
    // Close login modal (will be reopened by useEffect if needed)
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

  // Listen for force login events (from API 401 errors or token expiration)
  useEffect(() => {
    const handleForceLogin = async () => {
      console.warn('Force login event received - logging out and showing login modal')
      
      // Clear error first
      clearError()
      
      // Clear all local storage and cookies
      localStorage.removeItem('bearerToken')
      localStorage.removeItem('auth0UserId')
      
      // Clear Auth0 cache
      try {
        const keysToRemove = []
        for (let i = 0; i < localStorage.length; i++) {
          const key = localStorage.key(i)
          if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
            keysToRemove.push(key)
          }
        }
        keysToRemove.forEach(key => localStorage.removeItem(key))
      } catch (error) {
        console.error('Error clearing Auth0 cache:', error)
      }
      
      // Call Auth0 logout
      await logout()
      
      // Navigate to home to clear any protected routes (only once)
      navigate('/', { replace: true })
      
      // Set error message and show login modal after a brief delay
      setTimeout(() => {
        setError('Your session has expired. Please log in again.')
        setShowLoginModal(true)
      }, 200)
    }
    
    window.addEventListener('auth:force-login', handleForceLogin)
    return () => window.removeEventListener('auth:force-login', handleForceLogin)
  }, [navigate, logout, clearError])

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
    <Box sx={{ minHeight: '100vh', backgroundColor: 'background.default' }}>
      {isSellerAuthenticated && (
        <SellerNavbar onLogout={handleLogout} userEmail={userEmail} />
      )}
      
      <LoginModal
        isOpen={showLoginModal && !isSellerAuthenticated && !showSignupModal}
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
        onClose={async () => {
          // Clear error first
          clearError()
          
          // If not authenticated, ensure we're logged out and cleared
          if (!isSellerAuthenticated) {
            // Clear all local storage
            localStorage.removeItem('bearerToken')
            localStorage.removeItem('auth0UserId')
            
            // Clear Auth0 cache
            try {
              const keysToRemove = []
              for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i)
                if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
                  keysToRemove.push(key)
                }
              }
              keysToRemove.forEach(key => localStorage.removeItem(key))
            } catch (error) {
              console.error('Error clearing Auth0 cache:', error)
            }
            
            // Ensure logout is called
            await logout()
            
            // Navigate to home once
            navigate('/', { replace: true })
            
            // Show login modal after state settles
            setTimeout(() => {
              setShowLoginModal(true)
            }, 100)
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
              <Box sx={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                {/* Empty state - login modal will handle the UI */}
              </Box>
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
          path="/products"
          element={
            isSellerAuthenticated ? (
              <Products />
            ) : (
              <Navigate to="/" replace />
            )
          }
        />
        <Route
          path="/products/new"
          element={
            isSellerAuthenticated ? (
              <AddProduct />
            ) : (
              <Navigate to="/" replace />
            )
          }
        />
        <Route
          path="/products/:productId/edit"
          element={
            isSellerAuthenticated ? (
              <AddProduct />
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
        <Route
          path="/orders"
          element={
            isSellerAuthenticated ? (
              <SellerOrders userEmail={userEmail} />
            ) : (
              <Navigate to="/" replace />
            )
          }
        />
        <Route
          path="/shipments"
          element={
            isSellerAuthenticated ? (
              <Shipments userEmail={userEmail} />
            ) : (
              <Navigate to="/" replace />
            )
          }
        />
        <Route
          path="/payouts"
          element={
            isSellerAuthenticated ? (
              <Payouts />
            ) : (
              <Navigate to="/" replace />
            )
          }
        />
      </Routes>
    </Box>
  )
}

function App() {
  return (
    <BrowserRouter>
      <AppContent />
    </BrowserRouter>
  )
}

export default App

