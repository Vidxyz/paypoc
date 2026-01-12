import React from 'react'
import ReactDOM from 'react-dom/client'
import { ThemeProvider, CssBaseline } from '@mui/material'
import App from './App'
import theme from './theme'
import './index.css'
import { Auth0Provider } from './auth/Auth0Provider'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Auth0Provider>
        <App />
      </Auth0Provider>
    </ThemeProvider>
  </React.StrictMode>,
)

