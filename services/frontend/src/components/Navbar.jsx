import { Link, useLocation } from 'react-router-dom'
import { useState } from 'react'
import {
  AppBar,
  Toolbar,
  Typography,
  Button,
  Box,
  Badge,
  IconButton,
  Avatar,
  Menu,
  MenuItem,
  Divider,
  ListItemIcon,
  ListItemText,
} from '@mui/material'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import PersonIcon from '@mui/icons-material/Person'
import LogoutIcon from '@mui/icons-material/Logout'
import AccountCircleIcon from '@mui/icons-material/AccountCircle'
import HomeIcon from '@mui/icons-material/Home'
import ShoppingBagIcon from '@mui/icons-material/ShoppingBag'
import { useCart } from '../context/CartContext'
import CartPopup from './CartPopup'

function Navbar({ onLogout, buyerId, userEmail }) {
  const location = useLocation()
  const { getTotalItems } = useCart()
  const cartItemCount = getTotalItems()
  const [cartAnchorEl, setCartAnchorEl] = useState(null)
  const [profileAnchorEl, setProfileAnchorEl] = useState(null)
  const cartPopupOpen = Boolean(cartAnchorEl)
  const profileMenuOpen = Boolean(profileAnchorEl)

  const handleCartClick = (event) => {
    setCartAnchorEl(event.currentTarget)
  }

  const handleCartClose = () => {
    setCartAnchorEl(null)
  }

  const handleProfileClick = (event) => {
    setProfileAnchorEl(event.currentTarget)
  }

  const handleProfileClose = () => {
    setProfileAnchorEl(null)
  }

  const handleProfileMenuClick = (path) => {
    setProfileAnchorEl(null)
  }

  // Get user initial for avatar
  const userInitial = userEmail?.[0]?.toUpperCase() || 'U'

  return (
    <AppBar position="static" elevation={0}>
      <Toolbar sx={{ px: { xs: 2, sm: 3 }, py: 1.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexGrow: 1 }}>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 40,
              height: 40,
              borderRadius: 2,
              bgcolor: 'rgba(255, 255, 255, 0.2)',
            }}
          >
            <ShoppingCartIcon sx={{ fontSize: 24 }} />
          </Box>
          <Typography
            variant="h5"
            component={Link}
            to="/"
            sx={{
              textDecoration: 'none',
              color: 'inherit',
              fontWeight: 700,
              letterSpacing: '-0.02em',
            }}
          >
            BuyIt
          </Typography>
        </Box>
        
        <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center', mr: 2 }}>
          <Button
            component={Link}
            to="/"
            color="inherit"
            startIcon={<HomeIcon />}
            sx={{
              minWidth: 'auto',
              px: 2,
              py: 1,
              borderRadius: 2,
              bgcolor: location.pathname === '/' ? 'rgba(255, 255, 255, 0.15)' : 'transparent',
              '&:hover': {
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              },
            }}
          >
            Home
          </Button>
          <IconButton
            onClick={handleCartClick}
            color="inherit"
            sx={{
              borderRadius: 2,
              bgcolor: location.pathname === '/checkout' ? 'rgba(255, 255, 255, 0.15)' : 'transparent',
              '&:hover': {
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              },
            }}
          >
            <Badge badgeContent={cartItemCount} color="error" max={99}>
              <ShoppingCartIcon />
            </Badge>
          </IconButton>
          <CartPopup
            anchorEl={cartAnchorEl}
            open={cartPopupOpen}
            onClose={handleCartClose}
          />
          <Button
            component={Link}
            to="/orders"
            color="inherit"
            startIcon={<ShoppingBagIcon />}
            sx={{
              minWidth: 'auto',
              px: 2,
              py: 1,
              borderRadius: 2,
              bgcolor: location.pathname === '/orders' ? 'rgba(255, 255, 255, 0.15)' : 'transparent',
              '&:hover': {
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              },
            }}
          >
            Orders
          </Button>
        </Box>
        
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <IconButton
            onClick={handleProfileClick}
            sx={{
              p: 0.5,
              '&:hover': {
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              },
            }}
          >
            <Avatar
              sx={{
                width: 36,
                height: 36,
                bgcolor: 'rgba(255, 255, 255, 0.2)',
                color: 'white',
                fontWeight: 600,
                border: '2px solid rgba(255, 255, 255, 0.3)',
              }}
            >
              {userInitial}
            </Avatar>
          </IconButton>
          <Menu
            anchorEl={profileAnchorEl}
            open={profileMenuOpen}
            onClose={handleProfileClose}
            onClick={handleProfileClose}
            transformOrigin={{ horizontal: 'right', vertical: 'top' }}
            anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}
            PaperProps={{
              sx: {
                mt: 1.5,
                minWidth: 200,
                borderRadius: 2,
                boxShadow: '0 8px 16px rgba(0, 0, 0, 0.15)',
              },
            }}
          >
            <MenuItem component={Link} to="/profile" onClick={handleProfileMenuClick}>
              <ListItemIcon>
                <AccountCircleIcon fontSize="small" />
              </ListItemIcon>
              <ListItemText>My Profile</ListItemText>
            </MenuItem>
            <Divider />
            <Box sx={{ px: 2, py: 1 }}>
              <Typography variant="caption" color="text.secondary" display="block">
                {userEmail || 'User'}
              </Typography>
            </Box>
            <Divider />
            <MenuItem onClick={onLogout}>
              <ListItemIcon>
                <LogoutIcon fontSize="small" />
              </ListItemIcon>
              <ListItemText>Logout</ListItemText>
            </MenuItem>
          </Menu>
        </Box>
      </Toolbar>
    </AppBar>
  )
}

export default Navbar

