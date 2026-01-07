import { useNavigate } from 'react-router-dom'
import '../App.css'

function Home() {
  const navigate = useNavigate()

  return (
    <div className="page-container">
      <div className="card" style={{ textAlign: 'center', padding: '4rem 2rem' }}>
        <h1 style={{ fontSize: '3rem', marginBottom: '1rem', background: 'linear-gradient(135deg, #20b2aa 0%, #2ecc71 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>
          Welcome to BuyIt
        </h1>
        <p style={{ fontSize: '1.25rem', color: '#666', marginBottom: '3rem' }}>
          Your trusted payment platform for seamless transactions
        </p>
        <button
          onClick={() => navigate('/checkout')}
          className="btn btn-primary"
          style={{ fontSize: '1.25rem', padding: '1rem 2rem' }}
        >
          Go to Checkout →
        </button>
      </div>
      
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '2rem', marginTop: '2rem' }}>
        <div className="card">
          <h3>🛒 Easy Checkout</h3>
          <p style={{ color: '#666', marginTop: '1rem' }}>
            Create payments quickly and securely with our streamlined checkout process.
          </p>
        </div>
        
        <div className="card">
          <h3>📊 Payment History</h3>
          <p style={{ color: '#666', marginTop: '1rem' }}>
            View all your past transactions and track payment status in real-time.
          </p>
        </div>
        
        <div className="card">
          <h3>🔒 Secure Payments</h3>
          <p style={{ color: '#666', marginTop: '1rem' }}>
            Powered by Stripe, your payment information is always protected.
          </p>
        </div>
      </div>
    </div>
  )
}

export default Home

