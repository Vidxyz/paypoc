import { createContext, useContext, useEffect, useState, useRef } from 'react'
import { Auth0Client, createAuth0Client } from '@auth0/auth0-spa-js'

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
  const auth0ClientRef = useRef(null) // Ref for synchronous access
  const [isLoading, setIsLoading] = useState(true)
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [user, setUser] = useState(null)
  const [accessToken, setAccessToken] = useState(null)
  const [initError, setInitError] = useState(null)
  const [error, setError] = useState(null) // Runtime error for showing in modal
  const initPromiseRef = useRef(null) // Ref to store the initialization promise

  useEffect(() => {
    const initAuth0 = async () => {
      try {
        const domain = import.meta.env.VITE_AUTH0_DOMAIN
        const clientId = import.meta.env.VITE_AUTH0_CLIENT_ID
        const redirectUri = import.meta.env.VITE_AUTH0_REDIRECT_URI || window.location.origin

        if (!domain || !clientId) {
          const errorMsg = `Auth0 configuration missing. Domain: ${domain ? 'set' : 'missing'}, ClientId: ${clientId ? 'set' : 'missing'}. Please set VITE_AUTH0_DOMAIN and VITE_AUTH0_CLIENT_ID environment variables.`
          console.error(errorMsg)
          setInitError(errorMsg)
          setIsLoading(false)
          return
        }

        console.log('Initializing Auth0 with domain:', domain, 'clientId:', clientId, 'redirectUri:', redirectUri)

        const client = await createAuth0Client({
          domain,
          clientId,
          authorizationParams: {
            redirect_uri: redirectUri,
            // Request access token for API (must be configured to use signed tokens, not encrypted)
            audience: import.meta.env.VITE_AUTH0_AUDIENCE || undefined,
          },
          cacheLocation: 'localstorage',
          useRefreshTokens: true, // Enable refresh tokens
          useCookiesForTransactions: false, // Use localStorage for PKCE state
        })

        setAuth0Client(client)
        auth0ClientRef.current = client // Also store in ref for synchronous access

        // Check if we're handling a callback (Auth0 uses hash fragments for SPAs)
        const urlParams = new URLSearchParams(window.location.search)
        const hashParams = new URLSearchParams(window.location.hash.substring(1))
        const hasCode = urlParams.has('code') || hashParams.has('code')
        const hasError = urlParams.has('error') || hashParams.has('error')
        
        if (hasError) {
          const error = urlParams.get('error') || hashParams.get('error')
          const errorDescription = urlParams.get('error_description') || hashParams.get('error_description')
          const errorMsg = `Auth0 error: ${error}${errorDescription ? ` - ${errorDescription}` : ''}`
          console.error(errorMsg)
          setInitError(errorMsg)
          // Clear error from URL
          window.history.replaceState({}, document.title, window.location.pathname)
          setIsLoading(false)
          return
        }

        if (hasCode) {
          console.log('Detected Auth0 callback, handling redirect...')
          console.log('Full URL:', window.location.href)
          console.log('Origin:', window.location.origin)
          console.log('Pathname:', window.location.pathname)
          console.log('Search:', window.location.search)
          console.log('Hash:', window.location.hash)
          console.log('Configured redirectUri:', redirectUri)
          try {
            // handleRedirectCallback will exchange the code for tokens
            // This makes a POST to /oauth/token to exchange the code
            console.log('Calling handleRedirectCallback...')
            const result = await client.handleRedirectCallback()
            console.log('Auth0 callback handled successfully:', result)
            
            // Clear query params/hash after callback
            window.history.replaceState({}, document.title, window.location.pathname)
            
            // Verify authentication after callback
            const authenticated = await client.isAuthenticated()
            console.log('Auth0 authentication state after callback:', authenticated)
            setIsAuthenticated(authenticated)
            
            if (authenticated) {
              try {
                // Get access token (for API calls) - should be signed JWT if API is configured correctly
                let token = null
                try {
                  const tokenResponse = await client.getTokenSilently({ detailedResponse: true })
                  // Use access token if audience is configured, otherwise fall back to ID token
                  token = tokenResponse.access_token || tokenResponse.id_token
                  const tokenType = tokenResponse.access_token ? 'Access token' : 'ID token'
                  console.log(`Token retrieved after callback, type: ${tokenType}`)
                  
                  // Check if token is encrypted (JWE) - encrypted tokens have more than 3 parts
                  if (token && token.split('.').length > 3) {
                    console.warn('WARNING: Token appears to be encrypted (JWE). Check Auth0 API settings to disable JWE and use signed tokens instead.')
                  }
                } catch (error) {
                  console.error('Error getting token after callback:', error)
                }
                
                // Extract user info from token claims (more efficient than getUser() API call)
                let userProfile = null
                if (token) {
                  try {
                    userProfile = await getUserFromToken(client, token)
                    console.log('User profile extracted from token claims:', userProfile)
                  } catch (error) {
                    console.error('Error extracting user from token, falling back to getUser():', error)
                    userProfile = await client.getUser()
                  }
                } else {
                  // No token available, use getUser() as fallback
                  userProfile = await client.getUser()
                }
                
                setUser(userProfile)
                
                // Store token for API calls
                if (token) {
                  setAccessToken(token)
                  localStorage.setItem('bearerToken', token)
                  if (userProfile?.sub) {
                    localStorage.setItem('auth0UserId', userProfile.sub)
                  }
                }
              } catch (error) {
                console.error('Error getting user profile after callback:', error)
              }
            } else {
              console.warn('User not authenticated after callback - token exchange may have failed')
              const errorMsg = 'Authentication failed after callback. The token exchange returned 401 Unauthorized. This usually means:\n1. Auth0 application "Token Endpoint Authentication Method" is not set to "None" (required for SPAs)\n2. Missing or incorrect AUTH0_DOMAIN/AUTH0_CLIENT_ID environment variables at build time\n3. Client ID mismatch between build and Auth0 dashboard\n\nPlease check your Auth0 configuration and rebuild the frontend with correct environment variables.'
              console.error(errorMsg)
              setInitError(errorMsg)
              // Clear any stale PKCE state that might be causing issues
              try {
                const keysToRemove = []
                for (let i = 0; i < localStorage.length; i++) {
                  const key = localStorage.key(i)
                  if (key && (key.startsWith('@@auth0spajs@@') || key.includes('auth0'))) {
                    keysToRemove.push(key)
                  }
                }
                keysToRemove.forEach(key => localStorage.removeItem(key))
                console.log('Cleared Auth0 cache. Please try logging in again after fixing configuration.')
              } catch (e) {
                console.error('Error clearing Auth0 cache:', e)
              }
            }
            
            setIsLoading(false)
            return // Early return since we've handled the callback
          } catch (error) {
            console.error('Error handling Auth0 callback:', error)
            console.error('Error details:', {
              message: error.message,
              error: error.error,
              errorDescription: error.error_description,
              error_description: error.error_description,
              popup: error.popup,
              stack: error.stack
            })
            
            // Common errors and their meanings
            let errorMsg = error.message || error.error || 'Unknown error'
            if (error.error_description) {
              errorMsg += `: ${error.error_description}`
            }
            
            // Provide helpful messages for common errors
            if (error.statusCode === 401 || error.message?.includes('401') || error.error === 'unauthorized_client') {
              errorMsg += '\n\n401 Unauthorized Error - Common causes:\n'
              errorMsg += '1. Auth0 application "Token Endpoint Authentication Method" must be set to "None" (not "POST" or "Basic")\n'
              errorMsg += '2. Missing or incorrect AUTH0_DOMAIN/AUTH0_CLIENT_ID at build time\n'
              errorMsg += '3. Client ID mismatch - check Auth0 dashboard matches build config\n'
              errorMsg += '\nCheck Auth0 Dashboard → Applications → Your App → Settings → Advanced Settings → OAuth → Token Endpoint Authentication Method'
            } else if (error.error === 'invalid_grant' || error.error === 'access_denied') {
              errorMsg += '. This usually means the redirect_uri doesn\'t match or the code has expired. Check Auth0 configuration.'
            } else if (error.message && error.message.includes('redirect_uri')) {
              errorMsg += '. Redirect URI mismatch - ensure the redirect_uri in Auth0 matches exactly: ' + redirectUri
            }
            
            console.error('Final error message:', errorMsg)
            setInitError(errorMsg)
            setIsLoading(false)
            return
          }
        }

        // Get authentication state (only if not handling callback)
        const authenticated = await client.isAuthenticated()
        console.log('Auth0 authentication state:', authenticated)
        setIsAuthenticated(authenticated)

        if (authenticated) {
          try {
            // Get access token (for API calls) - should be signed JWT if API is configured correctly
            let token = null
            try {
              const tokenResponse = await client.getTokenSilently({ detailedResponse: true })
              // Use access token if audience is configured, otherwise fall back to ID token
              token = tokenResponse.access_token || tokenResponse.id_token
              const tokenType = tokenResponse.access_token ? 'Access token' : 'ID token'
              console.log(`Token retrieved, type: ${tokenType}`)
              
              // Check if token is encrypted (JWE) - encrypted tokens have more than 3 parts
              if (token && token.split('.').length > 3) {
                console.warn('WARNING: Token appears to be encrypted (JWE). Check Auth0 API settings to disable JWE and use signed tokens instead.')
              }
            } catch (error) {
              console.error('Error getting token:', error)
            }
            
            // Extract user info from token claims (more efficient than getUser() API call)
            let userProfile = null
            if (token) {
              try {
                userProfile = await getUserFromToken(client, token)
                console.log('User profile extracted from token claims:', userProfile)
              } catch (error) {
                console.error('Error extracting user from token, falling back to getUser():', error)
                userProfile = await client.getUser()
              }
            } else {
              // No token available, use getUser() as fallback
              userProfile = await client.getUser()
            }
            
            setUser(userProfile)

            // Store token for API calls
            if (token) {
              setAccessToken(token)
              localStorage.setItem('bearerToken', token)
              if (userProfile?.sub) {
                localStorage.setItem('auth0UserId', userProfile.sub)
              }
            }
          } catch (error) {
            console.error('Error getting user profile:', error)
            setInitError(`Failed to get user profile: ${error.message}`)
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
    console.log('Login called, auth0Client:', auth0Client, 'auth0ClientRef:', auth0ClientRef.current, 'isLoading:', isLoading, 'initError:', initError)
    
    // Wait for Auth0 client to be initialized if promise exists
    let client = auth0ClientRef.current || auth0Client
    if (initPromiseRef.current && !client) {
      console.log('Waiting for Auth0 initialization...')
      try {
        await initPromiseRef.current
        // Use ref for synchronous access (state might not be updated yet)
        client = auth0ClientRef.current || auth0Client
        console.log('Auth0 initialization complete, client:', client)
      } catch (error) {
        console.error('Error during Auth0 initialization:', error)
        setError(`Auth0 initialization failed: ${error.message}`)
        return
      }
    }
    
    // If there's an initialization error, log it and return
    if (initError) {
      console.error('Cannot login: Auth0 initialization failed:', initError)
      setError(`Auth0 configuration error: ${initError}`)
      return
    }
    
    // If client still not available, try to get from ref or state
    if (!client) {
      client = auth0ClientRef.current || auth0Client
      
      if (!client) {
        console.error('Auth0 client not initialized after waiting')
        const domain = import.meta.env.VITE_AUTH0_DOMAIN
        const clientId = import.meta.env.VITE_AUTH0_CLIENT_ID
        const errorMsg = `Auth0 client not initialized. Domain: ${domain ? 'set' : 'MISSING'}, ClientId: ${clientId ? 'set' : 'MISSING'}. Please check your environment variables and refresh the page.`
        console.error(errorMsg)
        setError(errorMsg)
        return
      }
    }
    
    try {
      const redirectUri = import.meta.env.VITE_AUTH0_REDIRECT_URI || window.location.origin
      console.log('Calling loginWithRedirect with redirectUri:', redirectUri, 'domain:', import.meta.env.VITE_AUTH0_DOMAIN)
      await client.loginWithRedirect({
        authorizationParams: {
          redirect_uri: redirectUri,
        },
      })
    } catch (error) {
      console.error('Error during login redirect:', error)
      setError(`Login failed: ${error.message || error.error || 'Unknown error'}`)
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

  const getAccessToken = async () => {
    if (!auth0Client) return null
    try {
      const token = await auth0Client.getTokenSilently()
      setAccessToken(token)
      localStorage.setItem('bearerToken', token)
      return token
    } catch (error) {
      console.error('Error getting access token:', error)
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
    initError, // Expose init error for debugging
    error, // Runtime error for modal display
    setError, // Allow clearing error
    clearError: () => setError(null), // Convenience method to clear error
  }

  return <Auth0Context.Provider value={value}>{children}</Auth0Context.Provider>
}

