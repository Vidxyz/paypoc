import React from 'react'
import { AppBar, Toolbar, Typography, Button, Box, Avatar, Menu, MenuItem, Divider, ListItemIcon, ListItemText, IconButton } from '@mui/material'
import { Logout, Dashboard, AccountCircle, MoneyOff, AccountBalance, Payment, Inventory } from '@mui/icons-material'
import { useNavigate, useLocation } from 'react-router-dom'

function AdminNavbar({ onLogout, userEmail }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [profileAnchorEl, setProfileAnchorEl] = React.useState(null)
  const profileMenuOpen = Boolean(profileAnchorEl)

  const handleProfileClick = (event) => {
    setProfileAnchorEl(event.currentTarget)
  }

  const handleProfileClose = () => {
    setProfileAnchorEl(null)
  }

  // Get user initial for avatar
  const userInitial = userEmail?.[0]?.toUpperCase() || 'A'

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
            <Dashboard sx={{ fontSize: 24 }} />
          </Box>
          <Typography
            variant="h5"
            component="div"
            sx={{
              fontWeight: 700,
              letterSpacing: '-0.02em',
            }}
          >
            Admin Console
          </Typography>
        </Box>
        
        <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center', mr: 2 }}>
          <Button
            color="inherit"
            startIcon={<MoneyOff />}
            onClick={() => navigate('/refunds')}
            sx={{
              minWidth: 'auto',
              px: 2,
              py: 1,
              borderRadius: 2,
              bgcolor: location.pathname === '/refunds' ? 'rgba(255, 255, 255, 0.15)' : 'transparent',
              '&:hover': {
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              },
            }}
          >
            Refunds
          </Button>
          <Button
            color="inherit"
            startIcon={<AccountBalance />}
            onClick={() => navigate('/reconciliation')}
            sx={{
              minWidth: 'auto',
              px: 2,
              py: 1,
              borderRadius: 2,
              bgcolor: location.pathname === '/reconciliation' ? 'rgba(255, 255, 255, 0.15)' : 'transparent',
              '&:hover': {
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              },
            }}
          >
            Reconciliation
          </Button>
          <Button
            color="inherit"
            startIcon={<Payment />}
            onClick={() => navigate('/payouts')}
            sx={{
              minWidth: 'auto',
              px: 2,
              py: 1,
              borderRadius: 2,
              bgcolor: location.pathname === '/payouts' ? 'rgba(255, 255, 255, 0.15)' : 'transparent',
              '&:hover': {
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              },
            }}
          >
            Payouts
          </Button>
          <Button
            color="inherit"
            startIcon={<Inventory />}
            onClick={() => navigate('/products')}
            sx={{
              minWidth: 'auto',
              px: 2,
              py: 1,
              borderRadius: 2,
              bgcolor: location.pathname === '/products' ? 'rgba(255, 255, 255, 0.15)' : 'transparent',
              '&:hover': {
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              },
            }}
          >
            Products
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
            <Box sx={{ px: 2, py: 1 }}>
              <Typography variant="caption" color="text.secondary" display="block">
                {userEmail || 'Admin User'}
              </Typography>
            </Box>
            <Divider />
            <MenuItem onClick={onLogout}>
              <ListItemIcon>
                <Logout fontSize="small" />
              </ListItemIcon>
              <ListItemText>Logout</ListItemText>
            </MenuItem>
          </Menu>
        </Box>
      </Toolbar>
    </AppBar>
  )
}

export default AdminNavbar

