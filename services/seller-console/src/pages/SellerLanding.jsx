import { Container, Card, CardContent, Typography, Box, Grid } from '@mui/material'
import StoreIcon from '@mui/icons-material/Store'
import PaymentIcon from '@mui/icons-material/Payment'
import TrendingUpIcon from '@mui/icons-material/TrendingUp'
import { useAuth0 } from '../auth/Auth0Provider'

function SellerLanding() {
  const { user } = useAuth0()
  const userEmail = user?.email || user?.['https://buyit.local/email'] || ''

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Box sx={{ mb: 4, textAlign: 'center' }}>
        <StoreIcon sx={{ fontSize: 64, color: 'primary.main', mb: 2 }} />
        <Typography variant="h3" component="h1" gutterBottom>
          Welcome to Seller Console
        </Typography>
        <Typography variant="h6" color="text.secondary">
          {userEmail && `Logged in as: ${userEmail}`}
        </Typography>
      </Box>

      <Grid container spacing={3}>
        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <PaymentIcon sx={{ fontSize: 48, color: 'primary.main', mb: 2 }} />
              <Typography variant="h5" gutterBottom>
                Manage Payments
              </Typography>
              <Typography variant="body2" color="text.secondary">
                View and manage your payment transactions, track earnings, and monitor your seller account balance.
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <TrendingUpIcon sx={{ fontSize: 48, color: 'primary.main', mb: 2 }} />
              <Typography variant="h5" gutterBottom>
                Track Performance
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Monitor your sales performance, view analytics, and track your growth over time.
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <StoreIcon sx={{ fontSize: 48, color: 'primary.main', mb: 2 }} />
              <Typography variant="h5" gutterBottom>
                Seller Dashboard
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Access your seller dashboard to manage your account settings and preferences.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Box sx={{ mt: 4, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          More features coming soon...
        </Typography>
      </Box>
    </Container>
  )
}

export default SellerLanding

