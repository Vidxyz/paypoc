import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { Box } from '@mui/material'
import AdminLogin from './pages/AdminLogin'
import AdminRefunds from './pages/AdminRefunds'
import AdminReconciliation from './pages/AdminReconciliation'
import AdminPayouts from './pages/AdminPayouts'
import AdminNavbar from './components/AdminNavbar'

function App() {
  const [isAdminAuthenticated, setIsAdminAuthenticated] = useState(false)

  useEffect(() => {
    // Check if admin is already logged in
    const storedAdminAuth = localStorage.getItem('isAdminAuthenticated')
    if (storedAdminAuth === 'true') {
      setIsAdminAuthenticated(true)
    }
  }, [])

  const handleAdminLogin = (username) => {
    setIsAdminAuthenticated(true)
    localStorage.setItem('isAdminAuthenticated', 'true')
  }

  const handleAdminLogout = () => {
    setIsAdminAuthenticated(false)
    localStorage.removeItem('isAdminAuthenticated')
  }

  return (
    <BrowserRouter>
      <Box sx={{ minHeight: '100vh', backgroundColor: 'background.default' }}>
        {isAdminAuthenticated && (
          <AdminNavbar onLogout={handleAdminLogout} />
        )}

        <Routes>
          <Route
            path="/login"
            element={
              isAdminAuthenticated ? (
                <Navigate to="/refunds" replace />
              ) : (
                <AdminLogin onLogin={handleAdminLogin} />
              )
            }
          />
          <Route
            path="/refunds"
            element={
              isAdminAuthenticated ? (
                <AdminRefunds />
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />
          <Route
            path="/reconciliation"
            element={
              isAdminAuthenticated ? (
                <AdminReconciliation />
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />
          <Route
            path="/payouts"
            element={
              isAdminAuthenticated ? (
                <AdminPayouts />
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />
          <Route
            path="/"
            element={
              isAdminAuthenticated ? (
                <Navigate to="/refunds" replace />
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />
        </Routes>
      </Box>
    </BrowserRouter>
  )
}

export default App

