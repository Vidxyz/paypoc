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

export const userApiClient = {
  /**
   * Sign up a new seller user (SELLER account)
   * This is currently public (no authentication required)
   * todo-vh: This should be secured with one-time passcode (OTP) for real user creation
   * @param {Object} signupData - Signup data
   * @param {string} signupData.email - User email
   * @param {string} signupData.password - User password
   * @param {string} signupData.firstname - User first name
   * @param {string} signupData.lastname - User last name
   * @returns {Promise<Object>} User response with id, email, firstname, lastname, account_type
   */
  sellerSignup: async (signupData) => {
    const response = await userApi.post('/users/seller/signup', {
      email: signupData.email,
      password: signupData.password,
      firstname: signupData.firstname,
      lastname: signupData.lastname,
      account_type: 'SELLER',
    })
    return response.data
  },
}

export default userApiClient

