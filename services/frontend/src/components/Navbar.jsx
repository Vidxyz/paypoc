import { Link, useLocation } from 'react-router-dom'
import {
  AppBar,
  Toolbar,
  Typography,
  Button,
  Box,
  Chip,
} from '@mui/material'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import PersonIcon from '@mui/icons-material/Person'
import LogoutIcon from '@mui/icons-material/Logout'

function Navbar({ onLogout, buyerId }) {
  const location = useLocation()

  return (
    <AppBar position="static" elevation={2}>
      <Toolbar>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexGrow: 1 }}>
          <ShoppingCartIcon />
          <Typography
            variant="h6"
            component={Link}
            to="/"
            sx={{
              textDecoration: 'none',
              color: 'inherit',
              fontWeight: 600,
            }}
          >
            BuyIt
          </Typography>
        </Box>
        
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', mr: 3 }}>
          <Button
            component={Link}
            to="/"
            color="inherit"
            variant={location.pathname === '/' ? 'outlined' : 'text'}
            sx={{ minWidth: 'auto', px: 2 }}
          >
            Home
          </Button>
          <Button
            component={Link}
            to="/checkout"
            color="inherit"
            variant={location.pathname === '/checkout' ? 'outlined' : 'text'}
            sx={{ minWidth: 'auto', px: 2 }}
          >
            Checkout
          </Button>
          <Button
            component={Link}
            to="/payments"
            color="inherit"
            variant={location.pathname === '/payments' ? 'outlined' : 'text'}
            sx={{ minWidth: 'auto', px: 2 }}
          >
            Payments
          </Button>
        </Box>
        
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          <Chip
            icon={<PersonIcon />}
            label={buyerId}
            variant="outlined"
            sx={{ borderColor: 'rgba(255, 255, 255, 0.5)', color: 'inherit' }}
          />
          <Button
            onClick={onLogout}
            color="inherit"
            variant="outlined"
            startIcon={<LogoutIcon />}
            size="small"
          >
            Logout
          </Button>
        </Box>
      </Toolbar>
    </AppBar>
  )
}

export default Navbar

