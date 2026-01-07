# BuyIt Frontend

A modern React frontend application for the BuyIt payment platform.

## Features

- 🔐 Simple login with static credentials (buyer123/buyer123)
- 🛒 Checkout page with Stripe Elements integration
- 📊 Payment history page showing all transactions
- 🎨 Modern, responsive UI design
- 🚀 Built with Vite for fast development and builds

## Tech Stack

- **React 18** - UI library
- **React Router** - Client-side routing
- **Vite** - Build tool and dev server
- **Stripe Elements** - Payment form components
- **Axios** - HTTP client
- **Nginx** - Production web server

## Development

### Prerequisites

- Node.js 18+
- npm or yarn

### Setup

```bash
# Install dependencies
npm install

# Start development server
npm run dev
```

The app will be available at `http://localhost:3000`

### Environment Variables

Create a `.env` file (see `.env.example`):

```env
VITE_API_BASE_URL=http://localhost:8080
VITE_STRIPE_PUBLISHABLE_KEY=pk_test_...
```

## Building for Production

```bash
npm run build
```

The built files will be in the `dist/` directory.

## Docker Build

### Development (uses test key by default)

```bash
docker build -t frontend:latest .
```

The Dockerfile includes a test Stripe publishable key as the default, which is safe to commit.

### Production (use environment variable)

```bash
# Set the live key from environment variable
export STRIPE_PUBLISHABLE_KEY=pk_live_YOUR_LIVE_KEY_HERE
docker build \
  --build-arg VITE_STRIPE_PUBLISHABLE_KEY="$STRIPE_PUBLISHABLE_KEY" \
  -t frontend:latest .
```

**Note**: Live keys should never be committed to git. Use CI/CD secrets or environment variables.

## Deployment

The frontend is deployed to Kubernetes using the files in `kubernetes/frontend/`.

Deploy using the main deploy script:

```bash
./scripts/deploy.sh
```

Or deploy just the frontend:

```bash
./scripts/deploy.sh frontend
```

## Access

After deployment, access the frontend at:
- **Ingress**: `http://buyit.local` (add to `/etc/hosts` if needed)
- **Port-forward**: `kubectl port-forward -n payments-platform svc/frontend 3000:80`

## Login Credentials

- Username: `buyer123`
- Password: `buyer123`

## Project Structure

```
src/
  ├── api/           # API client for payments service
  ├── components/    # Reusable React components
  ├── pages/         # Page components (Home, Checkout, Payments)
  ├── App.jsx        # Main app component with routing
  ├── App.css        # Global styles
  ├── main.jsx       # Entry point
  └── index.css      # Base styles
```

## Notes

- Payment IDs are stored in localStorage for the payments history page
- The frontend filters payments by `buyerId` to show only the logged-in user's payments
- Stripe test cards can be used for testing (e.g., 4242 4242 4242 4242)

