import { useState, useEffect } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Box,
  Grid,
  Avatar,
  Divider,
  Chip,
  CircularProgress,
  Alert,
  Paper,
} from '@mui/material'
import PersonIcon from '@mui/icons-material/Person'
import EmailIcon from '@mui/icons-material/Email'
import AccountCircleIcon from '@mui/icons-material/AccountCircle'
import { useAuth0 } from '../auth/Auth0Provider'
import { userApiClient } from '../api/userApi'

function Profile() {
  const { isAuthenticated, getAccessToken } = useAuth0()
  const [userInfo, setUserInfo] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (isAuthenticated) {
      loadUserInfo()
    }
  }, [isAuthenticated])

  const loadUserInfo = async () => {
    try {
      setLoading(true)
      setError(null)
      const token = await getAccessToken()
      if (!token) {
        throw new Error('No access token available')
      }
      const user = await userApiClient.getCurrentUser(token)
      setUserInfo(user)
    } catch (err) {
      console.error('Error loading user info:', err)
      setError(err.message || 'Failed to load profile information')
    } finally {
      setLoading(false)
    }
  }

  const getAccountTypeColor = (accountType) => {
    switch (accountType) {
      case 'BUYER':
        return 'primary'
      case 'SELLER':
        return 'success'
      case 'ADMIN':
        return 'error'
      default:
        return 'default'
    }
  }

  if (loading) {
    return (
      <Container maxWidth="md" sx={{ py: 4 }}>
        <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '400px' }}>
          <CircularProgress />
        </Box>
      </Container>
    )
  }

  if (error) {
    return (
      <Container maxWidth="md" sx={{ py: 4 }}>
        <Alert severity="error">{error}</Alert>
      </Container>
    )
  }

  if (!userInfo) {
    return (
      <Container maxWidth="md" sx={{ py: 4 }}>
        <Alert severity="warning">No user information available</Alert>
      </Container>
    )
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Typography variant="h4" component="h1" gutterBottom sx={{ fontWeight: 700, mb: 4 }}>
        My Profile
      </Typography>

      <Grid container spacing={3}>
        {/* Profile Header Card */}
        <Grid item xs={12}>
          <Card
            sx={{
              background: 'linear-gradient(135deg, #14b8a6 0%, #0d9488 100%)',
              color: 'white',
              borderRadius: 3,
              overflow: 'hidden',
            }}
          >
            <CardContent sx={{ p: 4 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 3 }}>
                <Avatar
                  sx={{
                    width: 100,
                    height: 100,
                    bgcolor: 'rgba(255, 255, 255, 0.2)',
                    border: '3px solid rgba(255, 255, 255, 0.3)',
                    fontSize: '3rem',
                  }}
                >
                  {userInfo.firstname?.[0]?.toUpperCase() || userInfo.email?.[0]?.toUpperCase() || 'U'}
                </Avatar>
                <Box sx={{ flex: 1 }}>
                  <Typography variant="h4" component="h2" sx={{ fontWeight: 700, mb: 1 }}>
                    {userInfo.firstname && userInfo.lastname
                      ? `${userInfo.firstname} ${userInfo.lastname}`
                      : userInfo.email}
                  </Typography>
                  {userInfo.firstname && userInfo.lastname && (
                    <Typography variant="body1" sx={{ opacity: 0.9, mb: 2 }}>
                      {userInfo.email}
                    </Typography>
                  )}
                  <Chip
                    label={userInfo.accountType || 'BUYER'}
                    color={getAccountTypeColor(userInfo.accountType)}
                    sx={{
                      bgcolor: 'rgba(255, 255, 255, 0.2)',
                      color: 'white',
                      fontWeight: 600,
                      border: '1px solid rgba(255, 255, 255, 0.3)',
                    }}
                  />
                </Box>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        {/* User Details Card */}
        <Grid item xs={12}>
          <Card sx={{ borderRadius: 3, boxShadow: 2 }}>
            <CardContent sx={{ p: 4 }}>
              <Typography variant="h6" component="h3" gutterBottom sx={{ fontWeight: 600, mb: 3 }}>
                Account Information
              </Typography>
              <Divider sx={{ mb: 3 }} />

              <Grid container spacing={3}>
                <Grid item xs={12} sm={6}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
                    <Box
                      sx={{
                        p: 1.5,
                        borderRadius: 2,
                        bgcolor: 'primary.light',
                        color: 'primary.contrastText',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      <PersonIcon />
                    </Box>
                    <Box>
                      <Typography variant="caption" color="text.secondary" display="block">
                        Full Name
                      </Typography>
                      <Typography variant="body1" sx={{ fontWeight: 500 }}>
                        {userInfo.firstname && userInfo.lastname
                          ? `${userInfo.firstname} ${userInfo.lastname}`
                          : 'Not provided'}
                      </Typography>
                    </Box>
                  </Box>
                </Grid>

                <Grid item xs={12} sm={6}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
                    <Box
                      sx={{
                        p: 1.5,
                        borderRadius: 2,
                        bgcolor: 'secondary.light',
                        color: 'secondary.contrastText',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      <EmailIcon />
                    </Box>
                    <Box>
                      <Typography variant="caption" color="text.secondary" display="block">
                        Email Address
                      </Typography>
                      <Typography variant="body1" sx={{ fontWeight: 500 }}>
                        {userInfo.email || 'Not provided'}
                      </Typography>
                    </Box>
                  </Box>
                </Grid>

                <Grid item xs={12} sm={6}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
                    <Box
                      sx={{
                        p: 1.5,
                        borderRadius: 2,
                        bgcolor: 'info.light',
                        color: 'info.contrastText',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      <AccountCircleIcon />
                    </Box>
                    <Box>
                      <Typography variant="caption" color="text.secondary" display="block">
                        Account Type
                      </Typography>
                      <Chip
                        label={userInfo.accountType || 'BUYER'}
                        color={getAccountTypeColor(userInfo.accountType)}
                        size="small"
                        sx={{ mt: 0.5 }}
                      />
                    </Box>
                  </Box>
                </Grid>

                <Grid item xs={12} sm={6}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
                    <Box
                      sx={{
                        p: 1.5,
                        borderRadius: 2,
                        bgcolor: 'grey.300',
                        color: 'grey.700',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      <Typography variant="h6" sx={{ fontWeight: 700 }}>
                        ID
                      </Typography>
                    </Box>
                    <Box>
                      <Typography variant="caption" color="text.secondary" display="block">
                        User ID
                      </Typography>
                      <Typography
                        variant="body2"
                        component="code"
                        sx={{
                          fontFamily: 'monospace',
                          fontSize: '0.875rem',
                          bgcolor: 'grey.100',
                          px: 1,
                          py: 0.5,
                          borderRadius: 1,
                        }}
                      >
                        {userInfo.id || 'N/A'}
                      </Typography>
                    </Box>
                  </Box>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Container>
  )
}

export default Profile

