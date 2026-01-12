import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
  Button,
  Typography,
  Box,
} from '@mui/material'
import ErrorIcon from '@mui/icons-material/Error'

function ErrorModal({ open, onClose, title, message, buttonText = 'OK' }) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="sm"
      fullWidth
      aria-labelledby="error-dialog-title"
      aria-describedby="error-dialog-description"
      sx={{
        zIndex: 1400, // Higher than LoginModal (default is 1300)
      }}
    >
      <DialogTitle
        id="error-dialog-title"
        sx={{ display: 'flex', alignItems: 'center', gap: 1, pb: 1 }}
      >
        <ErrorIcon color="error" sx={{ fontSize: 28 }} />
        <Typography variant="h6" component="span">
          {title}
        </Typography>
      </DialogTitle>
      <DialogContent>
        <Box sx={{ pt: 1 }}>
          <DialogContentText id="error-dialog-description">
            {message}
          </DialogContentText>
        </Box>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} variant="contained" color="error" fullWidth autoFocus>
          {buttonText}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

export default ErrorModal

