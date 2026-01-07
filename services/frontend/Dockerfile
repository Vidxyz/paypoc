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
ARG VITE_STRIPE_PUBLISHABLE_KEY=pk_test_51SmG1S2SQYvDNXZz5bkKmq1XENOih8R6WUPY9US0l2OXJr0HrkFTpMqhjy4Uo5cxMTT0bSpGmtrlaSPDk14RNgRC00Em4yYJ4q

ENV VITE_STRIPE_PUBLISHABLE_KEY=$VITE_STRIPE_PUBLISHABLE_KEY

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

