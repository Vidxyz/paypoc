import { useState } from 'react'
import './LoginModal.css'

function LoginModal({ isOpen, onClose, onLogin }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')

  if (!isOpen) return null

  const handleSubmit = (e) => {
    e.preventDefault()
    setError('')
    
    if (!username || !password) {
      setError('Please enter both username and password')
      return
    }

    const success = onLogin(username, password)
    if (!success) {
      setError('Invalid credentials. Use buyer123 / buyer123')
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <div className="modal-header">
          <h2>Welcome to BuyIt</h2>
          <p>Please log in to continue</p>
        </div>
        
        <form onSubmit={handleSubmit} className="login-form">
          {error && <div className="alert alert-error">{error}</div>}
          
          <div className="form-group">
            <label htmlFor="username">Username</label>
            <input
              type="text"
              id="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="Enter username"
              autoFocus
            />
          </div>
          
          <div className="form-group">
            <label htmlFor="password">Password</label>
            <input
              type="password"
              id="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Enter password"
            />
          </div>
          
          <button type="submit" className="btn btn-primary btn-block">
            Log In
          </button>
        </form>
        
        <div className="modal-footer">
          <p className="help-text">
            Demo credentials: <strong>buyer123</strong> / <strong>buyer123</strong>
          </p>
        </div>
      </div>
    </div>
  )
}

export default LoginModal

