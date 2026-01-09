import { createTheme } from '@mui/material/styles'

const theme = createTheme({
  palette: {
    primary: {
      main: '#20b2aa', // Teal
      light: '#2ecc71', // Light green
      dark: '#1a9b94',
      contrastText: '#ffffff',
    },
    secondary: {
      main: '#6c757d',
      light: '#868e96',
      dark: '#5a6268',
    },
    success: {
      main: '#28a745',
      light: '#34ce57',
      dark: '#218838',
    },
    warning: {
      main: '#ffc107',
      light: '#ffcd39',
      dark: '#e0a800',
    },
    error: {
      main: '#dc3545',
      light: '#e4606d',
      dark: '#c82333',
    },
    info: {
      main: '#17a2b8',
      light: '#3fc1d8',
      dark: '#138496',
    },
    background: {
      default: '#f5f5f5',
      paper: '#ffffff',
    },
  },
  typography: {
    fontFamily: [
      '-apple-system',
      'BlinkMacSystemFont',
      '"Segoe UI"',
      'Roboto',
      '"Helvetica Neue"',
      'Arial',
      'sans-serif',
    ].join(','),
    h1: {
      fontWeight: 700,
    },
    h2: {
      fontWeight: 600,
    },
    h3: {
      fontWeight: 600,
    },
    button: {
      textTransform: 'none',
      fontWeight: 600,
    },
  },
  shape: {
    borderRadius: 8,
  },
  components: {
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 8,
          padding: '0.75rem 1.5rem',
          fontSize: '1rem',
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          borderRadius: 12,
          boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)',
        },
      },
    },
  },
})

export default theme

