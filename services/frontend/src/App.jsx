import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useEffect } from 'react'
import Home from './pages/Home'
import Checkout from './pages/Checkout'
import Payments from './pages/Payments'
import LoginModal from './components/LoginModal'
import Navbar from './components/Navbar'
import './App.css'

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [showLoginModal, setShowLoginModal] = useState(false)
  const [buyerId, setBuyerId] = useState(null)

  useEffect(() => {
    // Check if user is already logged in
    const storedAuth = localStorage.getItem('isAuthenticated')
    const storedBuyerId = localStorage.getItem('buyerId')
    if (storedAuth === 'true' && storedBuyerId) {
      setIsAuthenticated(true)
      setBuyerId(storedBuyerId)
    }
  }, [])

  const handleLogin = (username, password) => {
    // Static credentials check
    if (username === 'buyer123' && password === 'buyer123') {
      setIsAuthenticated(true)
      setBuyerId('buyer123')
      localStorage.setItem('isAuthenticated', 'true')
      localStorage.setItem('buyerId', 'buyer123')
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
      <div className="app">
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
      </div>
    </BrowserRouter>
  )
}

export default App

