import { Routes, Route, Navigate } from 'react-router-dom'
import { Box } from '@mui/material'
import AdminNavbar from '../components/AdminNavbar'
import AdminRefunds from './AdminRefunds'
import AdminReconciliation from './AdminReconciliation'
import AdminPayouts from './AdminPayouts'

function AdminApp({ onLogout }) {
  return (
    <Box sx={{ minHeight: '100vh', backgroundColor: 'background.default' }}>
      <AdminNavbar onLogout={onLogout} />
      <Routes>
        <Route path="refunds" element={<AdminRefunds />} />
        <Route path="reconciliation" element={<AdminReconciliation />} />
        <Route path="payouts" element={<AdminPayouts />} />
        <Route path="" element={<Navigate to="refunds" replace />} />
      </Routes>
    </Box>
  )
}

export default AdminApp

