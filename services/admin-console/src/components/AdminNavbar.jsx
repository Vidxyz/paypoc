import { AppBar, Toolbar, Typography, Button, Box } from '@mui/material'
import { Logout, Dashboard } from '@mui/icons-material'
import { useNavigate } from 'react-router-dom'
import theme from '../theme'

function AdminNavbar({ onLogout }) {
  const navigate = useNavigate()

  return (
    <AppBar position="static" sx={{ backgroundColor: theme.palette.primary.main }}>
      <Toolbar>
        <Dashboard sx={{ mr: 2 }} />
        <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
          Admin Console
        </Typography>
        <Box sx={{ display: 'flex', gap: 2 }}>
          <Button color="inherit" onClick={() => navigate('/refunds')}>
            Refunds
          </Button>
          <Button color="inherit" onClick={() => navigate('/reconciliation')}>
            Reconciliation
          </Button>
          <Button color="inherit" onClick={() => navigate('/payouts')}>
            Payouts
          </Button>
          <Button
            color="inherit"
            startIcon={<Logout />}
            onClick={onLogout}
          >
            Logout
          </Button>
        </Box>
      </Toolbar>
    </AppBar>
  )
}

export default AdminNavbar

