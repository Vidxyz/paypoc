import axios from 'axios'

// User service API base URL
// Use ingress URL for browser access (user.local)
// In production, this should be proxied via nginx or use the ingress URL
const USER_API_BASE_URL = import.meta.env.VITE_USER_API_BASE_URL || 'https://user.local'

const userApi = axios.create({
  baseURL: USER_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Add request interceptor for error handling
userApi.interceptors.response.use(
  (response) => response,
  (error) => {
    // Extract error message from response
    if (error.response?.data?.error) {
      error.message = error.response.data.error
    } else if (error.response?.data?.details) {
      error.message = JSON.stringify(error.response.data.details)
    }
    return Promise.reject(error)
  }
)

export const userApiClient = {
  /**
   * Sign up a new user (BUYER account)
   * @param {Object} signupData - Signup data
   * @param {string} signupData.email - User email
   * @param {string} signupData.password - User password
   * @param {string} signupData.firstname - User first name
   * @param {string} signupData.lastname - User last name
   * @returns {Promise<Object>} User response with id, email, firstname, lastname, account_type
   */
  signup: async (signupData) => {
    const response = await userApi.post('/users/signup', {
      email: signupData.email,
      password: signupData.password,
      firstname: signupData.firstname,
      lastname: signupData.lastname,
      account_type: 'BUYER', // Always BUYER for frontend signup
    })
    return response.data
  },

  /**
   * Get current user information
   * @param {string} token - JWT access token
   * @returns {Promise<Object>} User information
   */
  getCurrentUser: async (token) => {
    const response = await userApi.get('/users/me', {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    })
    return response.data
  },
}

export default userApiClient

