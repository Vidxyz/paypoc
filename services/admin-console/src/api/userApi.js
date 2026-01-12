import axios from 'axios'

// User service API base URL
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
    if (error.response?.data?.error) {
      error.message = error.response.data.error
    } else if (error.response?.data?.details) {
      error.message = JSON.stringify(error.response.data.details)
    }
    return Promise.reject(error)
  }
)

// Add request interceptor to include bearer token for admin signup
userApi.interceptors.request.use(
  (config) => {
    // Only add token for admin signup endpoint
    if (config.url?.includes('/users/admin/signup')) {
      const token = localStorage.getItem('bearerToken')
      if (token) {
        config.headers.Authorization = `Bearer ${token}`
      }
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

export const userApiClient = {
  /**
   * Sign up a new admin user (ADMIN account)
   * This requires bearer token authentication - only logged-in ADMIN users can create new admins
   * @param {Object} signupData - Signup data
   * @param {string} signupData.email - User email
   * @param {string} signupData.password - User password
   * @param {string} signupData.firstname - User first name
   * @param {string} signupData.lastname - User last name
   * @returns {Promise<Object>} User response with id, email, firstname, lastname, account_type
   */
  adminSignup: async (signupData) => {
    const response = await userApi.post('/users/admin/signup', {
      email: signupData.email,
      password: signupData.password,
      firstname: signupData.firstname,
      lastname: signupData.lastname,
      account_type: 'ADMIN',
    })
    return response.data
  },
}

export default userApiClient

