import { useState, useEffect } from 'react'
import {
  Container,
  Card,
  CardContent,
  Typography,
  Button,
  Box,
  CircularProgress,
  Alert,
  Snackbar,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Grid,
} from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import VisibilityIcon from '@mui/icons-material/Visibility'
import {
  runReconciliation,
  getReconciliationRuns,
  getReconciliationRun,
} from '../api/adminApi'

function AdminReconciliation() {
  const [runs, setRuns] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [running, setRunning] = useState(false)
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' })
  const [detailsDialogOpen, setDetailsDialogOpen] = useState(false)
  const [selectedRun, setSelectedRun] = useState(null)
  const [detailsLoading, setDetailsLoading] = useState(false)
  const [runForm, setRunForm] = useState({
    startDate: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
    endDate: new Date().toISOString().split('T')[0],
    currency: '',
  })

  const loadRuns = async () => {
    setLoading(true)
    setError('')
    try {
      const response = await getReconciliationRuns(20)
      if (Array.isArray(response)) {
        setRuns(response)
      } else if (response.runs) {
        setRuns(response.runs)
      } else {
        setRuns([])
      }
    } catch (err) {
      setError(`Failed to load reconciliation runs: ${err.message}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadRuns()
  }, [])

  const handleRunReconciliation = async () => {
    setRunning(true)
    setError('')
    try {
      const startDate = new Date(runForm.startDate).toISOString()
      const endDate = new Date(runForm.endDate + 'T23:59:59').toISOString()
      const currency = runForm.currency.trim() || null

      const result = await runReconciliation(startDate, endDate, currency)
      setSnackbar({
        open: true,
        message: `Reconciliation completed. Found ${result.summary?.totalDiscrepancies || 0} discrepancies.`,
        severity: result.summary?.totalDiscrepancies > 0 ? 'warning' : 'success',
      })
      loadRuns()
    } catch (err) {
      setError(`Reconciliation failed: ${err.response?.data?.error || err.message}`)
      setSnackbar({
        open: true,
        message: `Reconciliation failed: ${err.response?.data?.error || err.message}`,
        severity: 'error',
      })
    } finally {
      setRunning(false)
    }
  }

  const handleViewDetails = async (run) => {
    setDetailsDialogOpen(true)
    setSelectedRun(run)
    setDetailsLoading(true)
    try {
      const details = await getReconciliationRun(run.id)
      // Handle both response formats: { run, discrepancies } or just the run object
      if (details.run) {
        setSelectedRun({ ...details.run, discrepancies: details.discrepancies || [] })
      } else if (details.discrepancies) {
        setSelectedRun({ ...details, discrepancies: details.discrepancies })
      } else {
        setSelectedRun(details)
      }
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Failed to load details: ${err.message}`,
        severity: 'error',
      })
    } finally {
      setDetailsLoading(false)
    }
  }

  const getSeverityColor = (severity) => {
    switch (severity?.toUpperCase()) {
      case 'CRITICAL':
        return 'error'
      case 'HIGH':
        return 'warning'
      case 'MEDIUM':
        return 'info'
      default:
        return 'default'
    }
  }

  return (
    <Container maxWidth="xl" sx={{ py: { xs: 3, sm: 4, md: 5 } }}>
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography 
            variant="h4" 
            component="h1" 
            gutterBottom
            sx={{ 
              fontWeight: 700,
              background: 'linear-gradient(135deg, #d32f2f 0%, #b71c1c 100%)',
              backgroundClip: 'text',
              WebkitBackgroundClip: 'text',
              WebkitTextFillColor: 'transparent',
            }}
          >
            Reconciliation
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            Compare Stripe balance transactions with ledger transactions to identify discrepancies
          </Typography>

          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                label="Start Date"
                type="date"
                value={runForm.startDate}
                onChange={(e) => setRunForm({ ...runForm, startDate: e.target.value })}
                InputLabelProps={{ shrink: true }}
              />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                label="End Date"
                type="date"
                value={runForm.endDate}
                onChange={(e) => setRunForm({ ...runForm, endDate: e.target.value })}
                InputLabelProps={{ shrink: true }}
              />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                label="Currency (optional)"
                value={runForm.currency}
                onChange={(e) => setRunForm({ ...runForm, currency: e.target.value.toUpperCase() })}
                placeholder="USD, CAD, etc."
              />
            </Grid>
          </Grid>

          <Button
            variant="contained"
            startIcon={running ? <CircularProgress size={16} /> : <PlayArrowIcon />}
            onClick={handleRunReconciliation}
            disabled={running}
          >
            Run Reconciliation
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
            <Typography variant="h5" component="h2">
              Past Reconciliation Runs
            </Typography>
            <Button
              variant="outlined"
              startIcon={<RefreshIcon />}
              onClick={loadRuns}
              disabled={loading}
            >
              Refresh
            </Button>
          </Box>

          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}

          {loading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
              <CircularProgress />
            </Box>
          ) : (
            <TableContainer component={Paper}>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Run Date</TableCell>
                    <TableCell>Date Range</TableCell>
                    <TableCell>Currency</TableCell>
                    <TableCell>Matched</TableCell>
                    <TableCell>Discrepancies</TableCell>
                    <TableCell>Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {runs.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={6} align="center">
                        <Typography variant="body2" color="text.secondary">
                          No reconciliation runs found
                        </Typography>
                      </TableCell>
                    </TableRow>
                  ) : (
                    runs.map((run) => (
                      <TableRow key={run.id}>
                        <TableCell>
                          {new Date(run.runAt || run.createdAt).toLocaleString()}
                        </TableCell>
                        <TableCell>
                          {new Date(run.startDate).toLocaleDateString()} -{' '}
                          {new Date(run.endDate).toLocaleDateString()}
                        </TableCell>
                        <TableCell>{run.currency || 'All'}</TableCell>
                        <TableCell>{run.matchedTransactions || 0}</TableCell>
                        <TableCell>
                          <Chip
                            label={run.totalDiscrepancies || 0}
                            color={run.totalDiscrepancies > 0 ? 'error' : 'success'}
                            size="small"
                          />
                        </TableCell>
                        <TableCell>
                          <Button
                            variant="outlined"
                            size="small"
                            startIcon={<VisibilityIcon />}
                            onClick={() => handleViewDetails(run)}
                          >
                            View Details
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      <Dialog
        open={detailsDialogOpen}
        onClose={() => setDetailsDialogOpen(false)}
        maxWidth="lg"
        fullWidth
      >
        <DialogTitle>Reconciliation Details</DialogTitle>
        <DialogContent>
          {detailsLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
              <CircularProgress />
            </Box>
          ) : selectedRun ? (
            <Box>
              <Typography variant="h6" gutterBottom>
                Summary
              </Typography>
              <Grid container spacing={2} sx={{ mb: 3 }}>
                <Grid item xs={6} sm={3}>
                  <Typography variant="body2" color="text.secondary">
                    Total Stripe Transactions
                  </Typography>
                  <Typography variant="h6">{selectedRun.totalStripeTransactions || 0}</Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="body2" color="text.secondary">
                    Total Ledger Transactions
                  </Typography>
                  <Typography variant="h6">{selectedRun.totalLedgerTransactions || 0}</Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="body2" color="text.secondary">
                    Matched
                  </Typography>
                  <Typography variant="h6" color="success.main">
                    {selectedRun.matchedTransactions || 0}
                  </Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="body2" color="text.secondary">
                    Discrepancies
                  </Typography>
                  <Typography variant="h6" color="error.main">
                    {selectedRun.totalDiscrepancies || 0}
                  </Typography>
                </Grid>
              </Grid>

              {selectedRun.discrepancies && selectedRun.discrepancies.length > 0 && (
                <>
                  <Typography variant="h6" gutterBottom>
                    Discrepancies
                  </Typography>
                  <TableContainer component={Paper} sx={{ maxHeight: 400 }}>
                    <Table stickyHeader>
                      <TableHead>
                        <TableRow>
                          <TableCell>Type</TableCell>
                          <TableCell>Severity</TableCell>
                          <TableCell>Stripe Amount</TableCell>
                          <TableCell>Ledger Amount</TableCell>
                          <TableCell>Currency</TableCell>
                          <TableCell>Description</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {selectedRun.discrepancies.map((disc, idx) => (
                          <TableRow key={idx}>
                            <TableCell>{disc.type}</TableCell>
                            <TableCell>
                              <Chip
                                label={disc.severity}
                                color={getSeverityColor(disc.severity)}
                                size="small"
                              />
                            </TableCell>
                            <TableCell>
                              {disc.stripeAmount != null
                                ? `$${(disc.stripeAmount / 100).toFixed(2)}`
                                : 'N/A'}
                            </TableCell>
                            <TableCell>
                              {disc.ledgerAmount != null
                                ? `$${(disc.ledgerAmount / 100).toFixed(2)}`
                                : 'N/A'}
                            </TableCell>
                            <TableCell>{disc.currency}</TableCell>
                            <TableCell>{disc.description}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </>
              )}
            </Box>
          ) : (
            <Typography>No details available</Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDetailsDialogOpen(false)}>Close</Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={() => setSnackbar({ ...snackbar, open: false })}
        message={snackbar.message}
      />
    </Container>
  )
}

export default AdminReconciliation

