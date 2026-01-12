# Build stage
FROM node:18-alpine AS builder

WORKDIR /app

# Copy package files
COPY package.json package-lock.json* ./

# Install dependencies
RUN npm ci

# Copy source code
COPY . .

# Set build-time environment variables (with defaults)
# Note: VITE_API_BASE_URL is no longer needed - we use /api which nginx proxies
# 
# VITE_STRIPE_PUBLISHABLE_KEY: Test key is safe to commit as default.
# For production, override via --build-arg using STRIPE_PUBLISHABLE_KEY env var.
# Live keys should come from CI/CD secrets, never committed to git.
ARG VITE_STRIPE_PUBLISHABLE_KEY=missing_stripe_publishable_key

# Auth0 configuration - these MUST be set at build time
ARG VITE_AUTH0_DOMAIN=your-tenant.auth0.com
ARG VITE_AUTH0_CLIENT_ID=
ARG VITE_AUTH0_REDIRECT_URI=
ARG VITE_AUTH0_AUDIENCE=

ENV VITE_STRIPE_PUBLISHABLE_KEY=$VITE_STRIPE_PUBLISHABLE_KEY
ENV VITE_AUTH0_DOMAIN=$VITE_AUTH0_DOMAIN
ENV VITE_AUTH0_CLIENT_ID=$VITE_AUTH0_CLIENT_ID
ENV VITE_AUTH0_REDIRECT_URI=$VITE_AUTH0_REDIRECT_URI
ENV VITE_AUTH0_AUDIENCE=$VITE_AUTH0_AUDIENCE

# Build the app
RUN npm run build

# Production stage
FROM nginx:alpine

# Copy built files from builder
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Expose port
EXPOSE 80

# Start nginx
CMD ["nginx", "-g", "daemon off;"]

