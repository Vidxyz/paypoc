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
import CheckCircleIcon from '@mui/icons-material/CheckCircle'

function SuccessModal({ open, onClose, title, message, buttonText = 'OK' }) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="sm"
      fullWidth
      aria-labelledby="success-dialog-title"
      aria-describedby="success-dialog-description"
    >
      <DialogTitle
        id="success-dialog-title"
        sx={{ display: 'flex', alignItems: 'center', gap: 1, pb: 1 }}
      >
        <CheckCircleIcon color="success" sx={{ fontSize: 28 }} />
        <Typography variant="h6" component="span">
          {title}
        </Typography>
      </DialogTitle>
      <DialogContent>
        <Box sx={{ pt: 1 }}>
          <DialogContentText id="success-dialog-description">
            {message}
          </DialogContentText>
        </Box>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} variant="contained" color="success" fullWidth autoFocus>
          {buttonText}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

export default SuccessModal

