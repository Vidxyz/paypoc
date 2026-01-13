import { useState, useEffect } from 'react'
import {
  Container,
  Typography,
  Box,
  Card,
  CardContent,
  Alert,
  CircularProgress,
  Chip,
  Divider,
  Button,
} from '@mui/material'
import { Person, Email, AccountCircle, CreditCard, Warning, Add } from '@mui/icons-material'
import { useAuth0 } from '../auth/Auth0Provider'
import { userApiClient } from '../api/userApi'
import { createPaymentsApiClient } from '../api/paymentsApi'
import AddStripeAccountModal from '../components/AddStripeAccountModal'

function SellerProfile() {
  const { isAuthenticated, getAccessToken } = useAuth0()
  const [user, setUser] = useState(null)
  const [stripeAccounts, setStripeAccounts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [addStripeModalOpen, setAddStripeModalOpen] = useState(false)
  const [selectedCurrency, setSelectedCurrency] = useState('CAD')

  useEffect(() => {
    if (!isAuthenticated) {
      return
    }

    const fetchData = async () => {
      try {
        setLoading(true)
        setError(null)

        // Setup API clients with authentication
        const paymentsApi = createPaymentsApiClient(getAccessToken)

        // Fetch user info and stripe accounts in parallel
        const [userData, stripeData] = await Promise.all([
          userApiClient.getCurrentUser(),
          paymentsApi.getSellerStripeAccounts().catch((err) => {
            console.warn('Failed to fetch Stripe accounts:', err)
            return [] // Return empty array if fetch fails
          }),
        ])

        setUser(userData)
        setStripeAccounts(stripeData || [])

        // Cleanup payments API interceptor
        paymentsApi.cleanup()
      } catch (err) {
        console.error('Failed to fetch seller profile:', err)
        setError(err.message || 'Failed to load seller profile')
      } finally {
        setLoading(false)
      }
    }

    fetchData()
  }, [isAuthenticated, getAccessToken])

  const handleAddStripeAccount = async (stripeAccountId, currency) => {
    try {
      const paymentsApi = createPaymentsApiClient(getAccessToken)
      await paymentsApi.updateStripeAccount(currency, stripeAccountId)
      paymentsApi.cleanup()
      
      // Refresh the data
      const paymentsApiRefresh = createPaymentsApiClient(getAccessToken)
      const [userData, stripeData] = await Promise.all([
        userApiClient.getCurrentUser(),
        paymentsApiRefresh.getSellerStripeAccounts().catch((err) => {
          console.warn('Failed to fetch Stripe accounts:', err)
          return []
        }),
      ])
      paymentsApiRefresh.cleanup()
      
      setUser(userData)
      setStripeAccounts(stripeData || [])
    } catch (err) {
      throw new Error(err.message || 'Failed to update Stripe account ID')
    }
  }

  const handleOpenAddModal = (currency = 'CAD') => {
    setSelectedCurrency(currency)
    setAddStripeModalOpen(true)
  }

  if (loading) {
    return (
      <Container maxWidth="md" sx={{ mt: 8, display: 'flex', justifyContent: 'center' }}>
        <CircularProgress />
      </Container>
    )
  }

  if (error) {
    return (
      <Container maxWidth="md" sx={{ mt: 8 }}>
        <Alert severity="error">{error}</Alert>
      </Container>
    )
  }

  // Check if seller has any Stripe accounts configured
  const hasStripeAccount = stripeAccounts.some((account) => account.stripeAccountId !== null)

  return (
    <Container maxWidth="md" sx={{ mt: 4, mb: 4 }}>
      <Typography variant="h4" component="h1" gutterBottom sx={{ mb: 4 }}>
        Seller Profile
      </Typography>

      {/* Stripe Account Banner */}
      {!hasStripeAccount && (
        <Alert
          severity="warning"
          icon={<Warning />}
          sx={{ mb: 4 }}
          action={
            <Button
              variant="contained"
              color="warning"
              size="small"
              startIcon={<Add />}
              onClick={() => handleOpenAddModal('CAD')}
            >
              Add Stripe Account
            </Button>
          }
        >
          <Typography variant="body2" fontWeight="bold">
            Stripe Account Required
          </Typography>
          <Typography variant="body2">
            You need to add your Stripe account ID to receive payments. Click "Add Stripe Account" to get started.
          </Typography>
        </Alert>
      )}

      {/* User Information Card */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
            <AccountCircle sx={{ fontSize: 40, mr: 2, color: 'primary.main' }} />
            <Typography variant="h5" component="h2">
              Account Information
            </Typography>
          </Box>
          <Divider sx={{ mb: 2 }} />
          {user && (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <Email color="action" />
                <Typography variant="body1">
                  <strong>Email:</strong> {user.email}
                </Typography>
              </Box>
              {user.firstname && (
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <Person color="action" />
                  <Typography variant="body1">
                    <strong>Name:</strong> {user.firstname} {user.lastname || ''}
                  </Typography>
                </Box>
              )}
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <Chip
                  label={user.account_type || 'SELLER'}
                  color="primary"
                  size="small"
                />
              </Box>
            </Box>
          )}
        </CardContent>
      </Card>

      {/* Stripe Accounts Card */}
      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
            <CreditCard sx={{ fontSize: 40, mr: 2, color: 'primary.main' }} />
            <Typography variant="h5" component="h2">
              Stripe Accounts
            </Typography>
          </Box>
          <Divider sx={{ mb: 2 }} />
          {stripeAccounts.length === 0 ? (
            <Alert severity="info">
              No Stripe accounts configured. Add your Stripe account ID to start receiving payments.
            </Alert>
          ) : (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              {stripeAccounts.map((account) => (
                <Box
                  key={`${account.sellerId}-${account.currency}`}
                  sx={{
                    p: 2,
                    border: '1px solid',
                    borderColor: 'divider',
                    borderRadius: 1,
                  }}
                >
                  <Typography variant="body1" fontWeight="bold">
                    {account.currency}
                  </Typography>
                  {account.stripeAccountId ? (
                    <Box>
                      <Typography variant="body2" color="text.secondary">
                        Stripe Account ID: {account.stripeAccountId}
                      </Typography>
                      <Button
                        size="small"
                        variant="outlined"
                        startIcon={<Add />}
                        onClick={() => handleOpenAddModal(account.currency)}
                        sx={{ mt: 1 }}
                      >
                        Update
                      </Button>
                    </Box>
                  ) : (
                    <Box>
                      <Alert severity="warning" sx={{ mt: 1, mb: 1 }}>
                        Stripe Account ID not configured
                      </Alert>
                      <Button
                        size="small"
                        variant="contained"
                        color="primary"
                        startIcon={<Add />}
                        onClick={() => handleOpenAddModal(account.currency)}
                      >
                        Add Stripe Account ID
                      </Button>
                    </Box>
                  )}
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                    Created: {new Date(account.createdAt).toLocaleDateString()}
                  </Typography>
                </Box>
              ))}
            </Box>
          )}
        </CardContent>
      </Card>

      {/* Add Stripe Account Modal */}
      <AddStripeAccountModal
        open={addStripeModalOpen}
        onClose={() => setAddStripeModalOpen(false)}
        onSuccess={handleAddStripeAccount}
        currency={selectedCurrency}
      />
    </Container>
  )
}

export default SellerProfile

