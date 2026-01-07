import { Link, useLocation } from 'react-router-dom'
import './Navbar.css'

function Navbar({ onLogout, buyerId }) {
  const location = useLocation()

  return (
    <nav className="navbar">
      <div className="navbar-container">
        <Link to="/" className="navbar-brand">
          <span className="brand-icon">🛒</span>
          BuyIt
        </Link>
        
        <div className="navbar-menu">
          <Link
            to="/"
            className={`navbar-link ${location.pathname === '/' ? 'active' : ''}`}
          >
            Home
          </Link>
          <Link
            to="/checkout"
            className={`navbar-link ${location.pathname === '/checkout' ? 'active' : ''}`}
          >
            Checkout
          </Link>
          <Link
            to="/payments"
            className={`navbar-link ${location.pathname === '/payments' ? 'active' : ''}`}
          >
            Payments
          </Link>
        </div>
        
        <div className="navbar-user">
          <span className="user-id">👤 {buyerId}</span>
          <button onClick={onLogout} className="btn btn-secondary btn-sm">
            Logout
          </button>
        </div>
      </div>
    </nav>
  )
}

export default Navbar

