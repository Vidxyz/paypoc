import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useEffect, useRef } from 'react'
import { Box, Typography } from '@mui/material'
import { useAuth0 } from './auth/Auth0Provider'
import AdminReconciliation from './pages/AdminReconciliation'
import AdminPayouts from './pages/AdminPayouts'
import AdminProducts from './pages/AdminProducts'
import AdminOrders from './pages/AdminOrders'
import AdminNavbar from './components/AdminNavbar'
import LoginModal from './components/LoginModal'
import SignupModal from './components/SignupModal'
import SuccessModal from './components/SuccessModal'
import ErrorModal from './components/ErrorModal'

// Production mode flag - set to false for nonproduction (development/testing)
export const IS_PRODUCTION = import.meta.env.VITE_IS_PRODUCTION === 'true' || false

function App() {
  const { isLoading, isAuthenticated, user, login, logout, initError, error, setError, clearError } = useAuth0()
  const [showLoginModal, setShowLoginModal] = useState(false)
  const [showSignupModal, setShowSignupModal] = useState(false)
  const [showSuccessModal, setShowSuccessModal] = useState(false)
  const [successMessage, setSuccessMessage] = useState('')

  // Extract account_type from JWT claims to verify ADMIN
  const accountType = user?.['https://buyit.local/account_type'] || user?.account_type
  const userEmail = user?.email || ''

  // Check if user is authenticated AND is an ADMIN
  const isAdminAuthenticated = isAuthenticated && accountType === 'ADMIN'

  useEffect(() => {
    // Show login modal if not authenticated and not loading
    if (!isLoading && !isAdminAuthenticated && !showLoginModal && !showSignupModal) {
      setShowLoginModal(true)
    }
  }, [isLoading, isAdminAuthenticated, showLoginModal, showSignupModal])

  const handleLogin = async () => {
    try {
      await login()
    } catch (error) {
      console.error('Login error:', error)
    }
  }

  const handleSignupSuccess = (user) => {
    setShowSignupModal(false)
    setSuccessMessage('Admin account created successfully! Please log in with your credentials.')
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

  // Listen for admin signup modal trigger
  useEffect(() => {
    const handleShowAdminSignup = () => {
      setShowLoginModal(false)
      setShowSignupModal(true)
    }
    window.addEventListener('showAdminSignup', handleShowAdminSignup)
    return () => window.removeEventListener('showAdminSignup', handleShowAdminSignup)
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
        {isAdminAuthenticated && (
          <AdminNavbar onLogout={handleLogout} userEmail={userEmail} />
        )}
        
        <LoginModal
          isOpen={showLoginModal && !isAdminAuthenticated && !showSignupModal && !error}
          onClose={() => setShowLoginModal(false)}
          onLogin={handleLogin}
        />
        
        <SignupModal
          isOpen={showSignupModal && !isAdminAuthenticated && !error}
          onClose={() => {
            setShowSignupModal(false)
            setShowLoginModal(true)
          }}
          onSignupSuccess={handleSignupSuccess}
        />

        <SuccessModal
          open={showSuccessModal}
          onClose={handleSuccessModalClose}
          title="Admin Account Created"
          message={successMessage}
          buttonText="Continue to Login"
        />

        <ErrorModal
          open={!!error}
          onClose={() => {
            clearError()
            // Show login modal again after error is dismissed
            if (!isAdminAuthenticated) {
              setShowLoginModal(true)
            }
          }}
          title="Authentication Error"
          message={error || ''}
          buttonText="OK"
        />

        <Routes>
          <Route
            path="/login"
            element={
              isAdminAuthenticated ? (
                <Navigate to="/orders" replace />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/reconciliation"
            element={
              isAdminAuthenticated ? (
                <AdminReconciliation />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/payouts"
            element={
              isAdminAuthenticated ? (
                <AdminPayouts />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/products"
            element={
              isAdminAuthenticated ? (
                <AdminProducts />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/orders"
            element={
              isAdminAuthenticated ? (
                <AdminOrders />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/"
            element={
              isAdminAuthenticated ? (
                <Navigate to="/orders" replace />
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

