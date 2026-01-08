import { useNavigate } from 'react-router-dom'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Button,
  Box,
  Grid,
} from '@mui/material'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import HistoryIcon from '@mui/icons-material/History'
import SecurityIcon from '@mui/icons-material/Security'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'

function Home() {
  const navigate = useNavigate()

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Card sx={{ textAlign: 'center', p: 4, mb: 4, background: 'linear-gradient(135deg, rgba(32, 178, 170, 0.1) 0%, rgba(46, 204, 113, 0.1) 100%)' }}>
        <Typography
          variant="h2"
          component="h1"
          sx={{
            mb: 2,
            background: 'linear-gradient(135deg, #20b2aa 0%, #2ecc71 100%)',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
            fontWeight: 700,
          }}
        >
          Welcome to BuyIt
        </Typography>
        <Typography variant="h6" color="text.secondary" sx={{ mb: 4 }}>
          Your trusted payment platform for seamless transactions
        </Typography>
        <Button
          onClick={() => navigate('/checkout')}
          variant="contained"
          size="large"
          endIcon={<ArrowForwardIcon />}
          sx={{ px: 4, py: 1.5 }}
        >
          Go to Checkout
        </Button>
      </Card>
      
      <Grid container spacing={3}>
        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                <ShoppingCartIcon color="primary" fontSize="large" />
                <Typography variant="h5" component="h3">
                  Easy Checkout
                </Typography>
              </Box>
              <Typography variant="body2" color="text.secondary">
                Create payments quickly and securely with our streamlined checkout process.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        
        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                <HistoryIcon color="primary" fontSize="large" />
                <Typography variant="h5" component="h3">
                  Payment History
                </Typography>
              </Box>
              <Typography variant="body2" color="text.secondary">
                View all your past transactions and track payment status in real-time.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        
        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                <SecurityIcon color="primary" fontSize="large" />
                <Typography variant="h5" component="h3">
                  Secure Payments
                </Typography>
              </Box>
              <Typography variant="body2" color="text.secondary">
                Powered by Stripe, your payment information is always protected.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Container>
  )
}

export default Home

