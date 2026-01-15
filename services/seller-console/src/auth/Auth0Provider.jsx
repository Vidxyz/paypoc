import { createContext, useContext, useEffect, useState, useRef } from 'react'
import { createAuth0Client } from '@auth0/auth0-spa-js'

const Auth0Context = createContext(null)

/**
 * Decodes a JWT token and returns the payload claims.
 * This is a simple decode (no verification) - we trust Auth0 tokens.
 * @param {string} token - JWT token string
 * @returns {object|null} Decoded payload claims or null if invalid
 */
function decodeJWT(token) {
  if (!token || typeof token !== 'string') {
    return null
  }
  
  try {
    // JWT format: header.payload.signature
    const parts = token.split('.')
    if (parts.length !== 3) {
      // Not a standard JWT - might be encrypted (JWE) which has more parts
      console.warn('Token does not appear to be a standard JWT (expected 3 parts, got', parts.length, ')')
      return null
    }
    
    // Decode the payload (second part)
    const payload = parts[1]
    
    // Base64URL decode
    // Replace URL-safe characters and add padding if needed
    let base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padding = base64.length % 4
    if (padding) {
      base64 += '='.repeat(4 - padding)
    }
    
    // Decode and parse JSON
    const decoded = JSON.parse(atob(base64))
    return decoded
  } catch (error) {
    console.error('Error decoding JWT token:', error)
    return null
  }
}

/**
 * Extracts user information from JWT token claims.
 * Falls back to getUser() if token is encrypted or invalid.
 * @param {Auth0Client} client - Auth0 client instance
 * @param {string} token - JWT token string
 * @returns {Promise<object>} User object with email, sub, and other claims
 */
async function getUserFromToken(client, token) {
  // Try to decode the token
  const claims = decodeJWT(token)
  
  if (claims && claims.email) {
    // Successfully decoded - extract user info from claims
    console.log('Extracted user info from token claims')
    return {
      email: claims.email,
      sub: claims.sub,
      name: claims.name,
      nickname: claims.nickname,
      picture: claims.picture,
      // Include custom claims
      'https://buyit.local/user_id': claims['https://buyit.local/user_id'],
      'https://buyit.local/auth0_user_id': claims['https://buyit.local/auth0_user_id'],
      'https://buyit.local/email': claims['https://buyit.local/email'],
      'https://buyit.local/account_type': claims['https://buyit.local/account_type'],
      'https://buyit.local/firstname': claims['https://buyit.local/firstname'],
      'https://buyit.local/lastname': claims['https://buyit.local/lastname'],
      // Include all other claims
      ...claims
    }
  }
  
  // Token is encrypted or invalid - fall back to getUser() API call
  console.log('Token is encrypted or invalid, falling back to getUser() API call')
  return await client.getUser()
}

export const useAuth0 = () => {
  const context = useContext(Auth0Context)
  if (!context) {
    throw new Error('useAuth0 must be used within Auth0Provider')
  }
  return context
}

export const Auth0Provider = ({ children }) => {
  const [auth0Client, setAuth0Client] = useState(null)
  const auth0ClientRef = useRef(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [user, setUser] = useState(null)
  const [accessToken, setAccessToken] = useState(null)
  const [initError, setInitError] = useState(null)
  const [error, setError] = useState(null)
  const initPromiseRef = useRef(null)

  useEffect(() => {
    const initAuth0 = async () => {
      try {
        const domain = import.meta.env.VITE_AUTH0_DOMAIN
        const clientId = import.meta.env.VITE_AUTH0_CLIENT_ID
        const redirectUri = import.meta.env.VITE_AUTH0_REDIRECT_URI || window.location.origin
        const audience = import.meta.env.VITE_AUTH0_AUDIENCE || undefined

        if (!domain || !clientId) {
          const errorMsg = `Auth0 configuration missing. Domain: ${domain ? 'set' : 'missing'}, ClientId: ${clientId ? 'set' : 'missing'}. Please set VITE_AUTH0_DOMAIN and VITE_AUTH0_CLIENT_ID environment variables.`
          console.error(errorMsg)
          setInitError(errorMsg)
          setIsLoading(false)
          return
        }

        console.log('Initializing Auth0 for seller console...')

        const client = await createAuth0Client({
          domain,
          clientId,
          authorizationParams: {
            redirect_uri: redirectUri,
            audience: audience,
          },
          cacheLocation: 'localstorage',
          useRefreshTokens: true,
          useCookiesForTransactions: false,
          // Use a unique cache key prefix to avoid conflicts with frontend app
          cacheKey: 'seller-console',
        })

        setAuth0Client(client)
        auth0ClientRef.current = client
        
        // Setup user API with authentication
        const { setupUserApi } = await import('../api/userApi')
        setupUserApi(async () => {
          const tokenResponse = await client.getTokenSilently({
            detailedResponse: true,
            authorizationParams: {
              audience: audience,
            },
          })
          return tokenResponse.access_token
        })

        // Check if we're handling a callback
        const urlParams = new URLSearchParams(window.location.search)
        const hashParams = new URLSearchParams(window.location.hash.substring(1))
        const hasCode = urlParams.has('code') || hashParams.has('code')
        const hasError = urlParams.has('error') || hashParams.has('error')

        if (hasError) {
          const error = urlParams.get('error') || hashParams.get('error')
          const errorDescription = urlParams.get('error_description') || hashParams.get('error_description')
          
          // Check if this is an access denied error from Auth0 Action (non-seller user)
          const isAccessDenied = error === 'access_denied' || 
                                 error === 'unauthorized' || 
                                 error === 'unauthorized_client' ||
                                 (errorDescription && errorDescription.toLowerCase().includes('access denied')) ||
                                 (errorDescription && errorDescription.toLowerCase().includes('unauthorized'))
          
          if (isAccessDenied) {
            console.warn('Auth0 Action denied access - user is not an SELLER')
            // Clear all Auth0 state and cache
            setIsAuthenticated(false)
            setUser(null)
            setAccessToken(null)
            localStorage.removeItem('bearerToken')
            localStorage.removeItem('auth0UserId')
            
            // Clear Auth0 localStorage cache completely
            try {
              const keysToRemove = []
              for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i)
                if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
                  keysToRemove.push(key)
                }
              }
              keysToRemove.forEach(key => localStorage.removeItem(key))
              console.log('Cleared Auth0 cache after access denied')
            } catch (error) {
              console.error('Error clearing Auth0 cache:', error)
            }
            
            // Set user-friendly error message (runtime error, not initError)
            setError('Access denied. Only SELLER users can access the seller console. Please log in with an SELLER account.')
            setInitError(null) // Clear initError so login can be retried
            window.history.replaceState({}, document.title, window.location.pathname)
            setIsLoading(false)
            return
          } else {
            // Other Auth0 errors (configuration issues, etc.)
            const errorMsg = `Auth0 error: ${error}${errorDescription ? ` - ${errorDescription}` : ''}`
            console.error(errorMsg)
            setInitError(errorMsg)
            window.history.replaceState({}, document.title, window.location.pathname)
            setIsLoading(false)
            return
          }
        }

        if (hasCode) {
          console.log('Detected Auth0 callback, handling redirect...')
          try {
            await client.handleRedirectCallback()
            window.history.replaceState({}, document.title, window.location.pathname)
            
            const authenticated = await client.isAuthenticated()
            setIsAuthenticated(authenticated)

            if (authenticated) {
              let token = null
              try {
                const tokenResponse = await client.getTokenSilently({ 
                  detailedResponse: true,
                  authorizationParams: {
                    audience: audience
                  }
                })
                // Always use access_token for API calls - ID tokens don't have the audience claim
                token = tokenResponse.access_token
                if (!token) {
                  throw new Error('No access token received from Auth0')
                }
                console.log('Access token retrieved after callback')
              } catch (error) {
                console.error('Error getting access token after callback:', error)
              }

              let userProfile = null
              if (token) {
                try {
                  userProfile = await getUserFromToken(client, token)
                } catch (error) {
                  console.error('Error extracting user from token, falling back to getUser():', error)
                  userProfile = await client.getUser()
                }
              } else {
                userProfile = await client.getUser()
              }
              
              // Immediately check if user is SELLER - if not, log out immediately
              const accountType = userProfile?.['https://buyit.local/account_type'] || userProfile?.account_type
              if (accountType !== 'SELLER') {
                console.warn('Non-SELLER user attempted to access seller console. Account type:', accountType)
                // Clear all state and log out immediately
                setIsAuthenticated(false)
                setUser(null)
                setAccessToken(null)
                localStorage.removeItem('bearerToken')
                localStorage.removeItem('auth0UserId')
                // Clear Auth0 cache by logging out (this will redirect, which is fine since we're already clearing state)
                try {
                  // Clear Auth0 localStorage cache manually
                  const keysToRemove = []
                  for (let i = 0; i < localStorage.length; i++) {
                    const key = localStorage.key(i)
                    if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
                      keysToRemove.push(key)
                    }
                  }
                  keysToRemove.forEach(key => localStorage.removeItem(key))
                  console.log('Cleared Auth0 cache for non-seller user')
                } catch (error) {
                  console.error('Error clearing Auth0 cache:', error)
                }
                // Set runtime error (not initError) so user can try again
                setError('Access denied. Only SELLER users can access the seller console. Please log in with an SELLER account.')
                setInitError(null) // Clear initError so login can be retried
                setIsLoading(false)
                return
              }

              setUser(userProfile)
              setAccessToken(token)
              localStorage.setItem('bearerToken', token)
              if (userProfile?.sub) {
                localStorage.setItem('auth0UserId', userProfile.sub)
              }
            } else {
              console.warn('User not authenticated after callback')
              setInitError('Authentication failed after callback')
            }
            
            setIsLoading(false)
            return
          } catch (error) {
            console.error('Error handling Auth0 callback:', error)
            
            // Check if this is an access denied/unauthorized error from Auth0 Action
            const errorMessage = error.message || error.error || String(error)
            const isAccessDenied = errorMessage.includes('access_denied') ||
                                   errorMessage.includes('unauthorized') ||
                                   errorMessage.includes('Unauthorized') ||
                                   error.status === 401 ||
                                   error.error === 'access_denied' ||
                                   error.error === 'unauthorized'
            
            if (isAccessDenied) {
              console.warn('Auth0 Action denied access during callback - user is not an SELLER')
              // Clear all Auth0 state and cache
              setIsAuthenticated(false)
              setUser(null)
              setAccessToken(null)
              localStorage.removeItem('bearerToken')
              localStorage.removeItem('auth0UserId')
              
              // Clear Auth0 localStorage cache completely
              try {
                const keysToRemove = []
                for (let i = 0; i < localStorage.length; i++) {
                  const key = localStorage.key(i)
                  if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
                    keysToRemove.push(key)
                  }
                }
                keysToRemove.forEach(key => localStorage.removeItem(key))
                console.log('Cleared Auth0 cache after access denied in callback')
              } catch (clearError) {
                console.error('Error clearing Auth0 cache:', clearError)
              }
              
              // Set user-friendly error message (runtime error, not initError)
              setError('Access denied. Only SELLER users can access the seller console. Please log in with an SELLER account.')
              setInitError(null) // Clear initError so login can be retried
              setIsLoading(false)
              return
            } else {
              // Other errors (network, configuration, etc.)
              setInitError(errorMessage || 'Failed to handle Auth0 callback')
              setIsLoading(false)
              return
            }
          }
        }

        // Check existing authentication state
        const authenticated = await client.isAuthenticated()
        setIsAuthenticated(authenticated)

        if (authenticated) {
          let token = null
          try {
            const tokenResponse = await client.getTokenSilently({ 
              detailedResponse: true,
              authorizationParams: {
                audience: audience
              }
            })
            // Always use access_token for API calls - ID tokens don't have the audience claim
            token = tokenResponse.access_token
            if (!token) {
              throw new Error('No access token received from Auth0')
            }
          } catch (error) {
            console.error('Error getting access token on init:', error)
          }

          let userProfile = null
          if (token) {
            try {
              userProfile = await getUserFromToken(client, token)
            } catch (error) {
              console.error('Error extracting user from token, falling back to getUser():', error)
              userProfile = await client.getUser()
            }
          } else {
            userProfile = await client.getUser()
          }
          
          // Immediately check if user is SELLER - if not, log out immediately
          const accountType = userProfile?.['https://buyit.local/account_type'] || userProfile?.account_type
          if (accountType !== 'SELLER') {
            console.warn('Non-SELLER user attempted to access seller console. Account type:', accountType)
            // Clear all state and log out immediately
            setIsAuthenticated(false)
            setUser(null)
            setAccessToken(null)
            localStorage.removeItem('bearerToken')
            localStorage.removeItem('auth0UserId')
            // Clear Auth0 cache manually
            try {
              // Clear Auth0 localStorage cache manually
              const keysToRemove = []
              for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i)
                if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
                  keysToRemove.push(key)
                }
              }
              keysToRemove.forEach(key => localStorage.removeItem(key))
              console.log('Cleared Auth0 cache for non-seller user')
            } catch (error) {
              console.error('Error clearing Auth0 cache:', error)
            }
            // Set runtime error (not initError) so user can try again
            setError('Access denied. Only SELLER users can access the seller console. Please log in with an SELLER account.')
            setInitError(null) // Clear initError so login can be retried
            setIsLoading(false)
            return
          }

          setUser(userProfile)
          setAccessToken(token)
          localStorage.setItem('bearerToken', token)
          if (userProfile?.sub) {
            localStorage.setItem('auth0UserId', userProfile.sub)
          }
        }
      } catch (error) {
        console.error('Error initializing Auth0:', error)
        setInitError(error.message || 'Failed to initialize Auth0')
      } finally {
        setIsLoading(false)
      }
    }

    const promise = initAuth0()
    initPromiseRef.current = promise
  }, [])

  const login = async () => {
    // Clear any previous runtime errors when attempting a new login
    setError(null)
    
    // Clear access denied initErrors so user can retry
    if (initError && (initError.includes('access_denied') || 
                      initError.includes('unauthorized') || 
                      initError.includes('Unauthorized') ||
                      initError.includes('Access denied'))) {
      setInitError(null)
    }
    
    let client = auth0ClientRef.current || auth0Client
    if (initPromiseRef.current && !client) {
      try {
        await initPromiseRef.current
        client = auth0ClientRef.current || auth0Client
      } catch (error) {
        console.error('Error during Auth0 initialization:', error)
        setError(`Auth0 initialization failed: ${error.message}`)
        return
      }
    }

    // Only block login if initError is a real configuration error (not access denied/unauthorized)
    // Access denied errors are handled as runtime errors, not init errors
    if (initError && 
        !initError.includes('Access denied') && 
        !initError.includes('access_denied') &&
        !initError.includes('unauthorized') &&
        !initError.includes('Unauthorized')) {
      setError(`Auth0 configuration error: ${initError}`)
      return
    }
    
    // If initError is an access denied error, clear it so user can retry
    if (initError && (initError.includes('access_denied') || 
                      initError.includes('unauthorized') || 
                      initError.includes('Unauthorized'))) {
      setInitError(null)
    }

    if (!client) {
      const errorMsg = 'Auth0 client not initialized'
      setError(errorMsg)
      return
    }

    try {
      const redirectUri = import.meta.env.VITE_AUTH0_REDIRECT_URI || window.location.origin
      console.log('Initiating login redirect to:', redirectUri)
      // Force re-authentication by using prompt: 'login'
      // This ensures users must enter credentials again, even if they have a cached session
      await client.loginWithRedirect({
        authorizationParams: {
          redirect_uri: redirectUri,
          prompt: 'login', // Force login prompt, don't use cached credentials
        },
      })
    } catch (error) {
      console.error('Error during login redirect:', error)
      setError(`Login failed: ${error.message}`)
    }
  }

  const logout = async () => {
    if (!auth0Client) return
    localStorage.removeItem('bearerToken')
    localStorage.removeItem('auth0UserId')
    await auth0Client.logout({
      logoutParams: {
        returnTo: window.location.origin,
      },
    })
  }

  /**
   * Checks if a JWT token is expired
   * @param {string} token - JWT token string
   * @returns {boolean} True if token is expired or invalid
   */
  const isTokenExpired = (token) => {
    if (!token) return true
    
    try {
      const claims = decodeJWT(token)
      if (!claims || !claims.exp) return true
      
      // exp is in seconds, Date.now() is in milliseconds
      const expirationTime = claims.exp * 1000
      const now = Date.now()
      
      // Consider token expired if it expires within the next 5 seconds (buffer)
      return now >= (expirationTime - 5000)
    } catch (error) {
      console.error('Error checking token expiration:', error)
      return true
    }
  }

  const forceLogout = () => {
    console.warn('Forcing logout due to expired or missing token')
    setIsAuthenticated(false)
    setUser(null)
    setAccessToken(null)
    localStorage.removeItem('bearerToken')
    localStorage.removeItem('auth0UserId')
    
    // Clear Auth0 cache
    try {
      const keysToRemove = []
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
          keysToRemove.push(key)
        }
      }
      keysToRemove.forEach(key => localStorage.removeItem(key))
    } catch (error) {
      console.error('Error clearing Auth0 cache:', error)
    }
    
    setError('Your session has expired. Please log in again.')
    // Dispatch event to show login modal
    window.dispatchEvent(new CustomEvent('auth:force-login'))
  }

  const getAccessToken = async () => {
    if (!auth0Client) {
      forceLogout()
      return null
    }
    
    try {
      const audience = import.meta.env.VITE_AUTH0_AUDIENCE || undefined
      const tokenResponse = await auth0Client.getTokenSilently({ 
        detailedResponse: true,
        authorizationParams: {
          audience: audience
        }
      })
      // Always use access_token for API calls - ID tokens don't have the audience claim
      const token = tokenResponse.access_token
      if (!token) {
        throw new Error('No access token received from Auth0')
      }
      
      // Check if token is expired
      if (isTokenExpired(token)) {
        forceLogout()
        return null
      }
      
      setAccessToken(token)
      localStorage.setItem('bearerToken', token)
      return token
    } catch (error) {
      console.error('Error getting access token:', error)
      
      // If token retrieval fails due to expiration or login required, force logout
      if (error.error === 'login_required' || 
          error.error === 'consent_required' ||
          error.message?.includes('login_required') ||
          error.message?.includes('expired') ||
          error.message?.includes('invalid_token')) {
        forceLogout()
      }
      
      return null
    }
  }

  const value = {
    isLoading,
    isAuthenticated,
    user,
    accessToken,
    login,
    logout,
    getAccessToken,
    forceLogout,
    initError,
    error,
    setError,
    clearError: () => setError(null),
  }

  return <Auth0Context.Provider value={value}>{children}</Auth0Context.Provider>
}

