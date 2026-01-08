import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { Box } from '@mui/material'
import Home from './pages/Home'
import Checkout from './pages/Checkout'
import Payments from './pages/Payments'
import LoginModal from './components/LoginModal'
import Navbar from './components/Navbar'

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [showLoginModal, setShowLoginModal] = useState(false)
  const [buyerId, setBuyerId] = useState(null)

  useEffect(() => {
    // Check if user is already logged in
    const storedAuth = localStorage.getItem('isAuthenticated')
    const storedBuyerId = localStorage.getItem('buyerId')
    const storedToken = localStorage.getItem('bearerToken')
    if (storedAuth === 'true' && storedBuyerId) {
      setIsAuthenticated(true)
      setBuyerId(storedBuyerId)
      // If bearerToken is missing but user is authenticated, restore it
      // This handles cases where localStorage was partially cleared
      if (!storedToken && storedBuyerId === 'buyer123') {
        localStorage.setItem('bearerToken', 'buyer123_token')
      }
    }
  }, [])

  const handleLogin = (username, password) => {
    // Static credentials check
    if (username === 'buyer123' && password === 'buyer123') {
      setIsAuthenticated(true)
      setBuyerId('buyer123')
      // Store authentication state
      localStorage.setItem('isAuthenticated', 'true')
      localStorage.setItem('buyerId', 'buyer123')
      // Store bearer token (static for now - in production this would come from login API)
      localStorage.setItem('bearerToken', 'buyer123_token')
      setShowLoginModal(false)
      return true
    }
    return false
  }

  const handleLogout = () => {
    setIsAuthenticated(false)
    setBuyerId(null)
    localStorage.removeItem('isAuthenticated')
    localStorage.removeItem('buyerId')
    localStorage.removeItem('bearerToken')
    setShowLoginModal(true)
  }

  // Show login modal if not authenticated
  useEffect(() => {
    if (!isAuthenticated && !showLoginModal) {
      setShowLoginModal(true)
    }
  }, [isAuthenticated, showLoginModal])

  return (
    <BrowserRouter>
      <Box sx={{ minHeight: '100vh', backgroundColor: 'background.default' }}>
        {isAuthenticated && (
          <Navbar onLogout={handleLogout} buyerId={buyerId} />
        )}
        
        <LoginModal
          isOpen={showLoginModal && !isAuthenticated}
          onClose={() => {}}
          onLogin={handleLogin}
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
                <Checkout buyerId={buyerId} />
              ) : (
                <Navigate to="/" replace />
              )
            }
          />
          <Route
            path="/payments"
            element={
              isAuthenticated ? (
                <Payments buyerId={buyerId} />
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

