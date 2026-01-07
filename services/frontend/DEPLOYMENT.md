# Frontend Deployment Guide

## Overview

The BuyIt frontend is a React application built with Vite, deployed to Kubernetes with Nginx serving the static build.

## Quick Start

1. **Build and deploy everything:**
   ```bash
   ./scripts/deploy.sh
   ```

2. **Or deploy just the frontend:**
   ```bash
   ./scripts/deploy.sh frontend
   ```

3. **Access the frontend:**
   - Add to `/etc/hosts`: `<minikube-ip> buyit.local`
   - Visit: `http://buyit.local`

## Architecture

### Build Process

1. **Vite Build**: React app is built into static files
2. **Docker Build**: Multi-stage build creates Nginx image with static files
3. **Kubernetes**: Deployment serves the Nginx container

### Environment Variables

Vite requires environment variables at **build time**, not runtime. They're baked into the JavaScript bundle.

Build-time variables (set in Dockerfile):
- `VITE_API_BASE_URL` - Payments service URL
- `VITE_STRIPE_PUBLISHABLE_KEY` - Stripe publishable key

### Kubernetes Resources

- **Namespace**: `payments-platform`
- **ConfigMap**: `frontend-config` (for reference, not used at runtime)
- **Deployment**: `frontend` (2 replicas)
- **Service**: `frontend` (ClusterIP, port 80)
- **Ingress**: `frontend-ingress` (host: `buyit.local`)

## Development

### Local Development

```bash
cd services/frontend
npm install
npm run dev
```

Access at `http://localhost:3000`

### Building Locally

```bash
npm run build
```

Output in `dist/` directory.

## Troubleshooting

### Frontend not accessible

1. Check ingress:
   ```bash
   kubectl get ingress -n payments-platform
   ```

2. Check if ingress controller is running:
   ```bash
   kubectl get pods -n ingress-nginx
   ```

3. Use port-forward as fallback:
   ```bash
   kubectl port-forward -n payments-platform svc/frontend 3000:80
   ```

### API calls failing

1. Check if payments service is running:
   ```bash
   kubectl get pods -n payments-platform | grep payments
   ```

2. Verify API URL in browser console (should be the Kubernetes service URL)

3. Check CORS settings if calling from browser directly

### Build issues

If environment variables aren't working:
- They must be set during Docker build (via `--build-arg`)
- Check Dockerfile ARG/ENV declarations
- Rebuild the image if variables change

## Notes

- The frontend uses localStorage to store payment IDs for the payments history page
- Login is static (buyer123/buyer123) - will be replaced with OIDC later
- Stripe test mode is used - replace keys for production

