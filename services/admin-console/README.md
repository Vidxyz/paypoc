# Admin Console

Separate frontend application for administrative operations on the payments platform.

## Features

- **Refund Management**: View all payments and refund any captured transaction
- **Reconciliation**: Run reconciliation jobs to compare Stripe balance transactions with ledger transactions
- **Payout Management**: View all sellers with their balances and initiate payouts

## Access

- **URL**: http://admin.local (or via port-forward: `kubectl port-forward -n payments-platform svc/admin-console 3001:80`)
- **Credentials**: 
  - Username: `admin`
  - Password: `admin`

## Development

```bash
cd services/admin-console
npm install
npm run dev
```

The admin console runs on port 3001 in development mode.

## Deployment

The admin console is deployed via `scripts/deploy.sh` along with other services.

## API Integration

The admin console communicates with the payments service via `/api` endpoints, which are proxied by nginx to the payments service.

