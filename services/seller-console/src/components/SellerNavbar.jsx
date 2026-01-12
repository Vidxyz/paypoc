import { AppBar, Toolbar, Typography, Button, Box, Chip } from '@mui/material'
import { Logout, Store, Person } from '@mui/icons-material'
import { useNavigate } from 'react-router-dom'
import theme from '../theme'

function SellerNavbar({ onLogout, userEmail }) {
  const navigate = useNavigate()
  
  return (
    <AppBar position="static" sx={{ backgroundColor: theme.palette.primary.main }}>
      <Toolbar>
        <Store sx={{ mr: 2 }} />
        <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
          Seller Console
        </Typography>
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
          <Button color="inherit" onClick={() => navigate('/dashboard')}>
            Dashboard
          </Button>
          <Button color="inherit" onClick={() => navigate('/profile')}>
            Profile
          </Button>
          {userEmail && (
            <Chip
              icon={<Person />}
              label={userEmail}
              variant="outlined"
              sx={{ borderColor: 'rgba(255, 255, 255, 0.5)', color: 'inherit' }}
            />
          )}
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

export default SellerNavbar

