# Deployment Guide

Quick reference for deploying the Payments Platform to minikube.

## Prerequisites

1. **Minikube** running:
   ```bash
   minikube start
   minikube addons enable ingress  # Required for Ingress
   ```

2. **Kafka and PostgreSQL** deployed (via Terraform):
   ```bash
   cd infra/minikube
   terraform apply
   ```

3. **kubectl** configured:
   ```bash
   kubectl config use-context minikube
   ```

## Quick Deploy

```bash
./scripts/deploy.sh
```

This will:
- Build Docker images
- Load images into minikube
- Deploy PostgreSQL
- Deploy Ledger Service
- Deploy Payments Service
- Wait for all services to be ready

## Deploy Individual Components

```bash
# Build images only
./scripts/deploy.sh build

# Deploy PostgreSQL only
./scripts/deploy.sh postgres

# Deploy Ledger Service only
./scripts/deploy.sh ledger

# Deploy Payments Service only
./scripts/deploy.sh payments

# Check status
./scripts/deploy.sh status
```

## Access Services

### Payments Service
```bash
# Via Ingress (recommended)
# 1. Add to /etc/hosts
echo "$(minikube ip) payments.local" | sudo tee -a /etc/hosts

# 2. Access
curl http://payments.local/swagger-ui.html

# Or via port-forward (alternative)
kubectl port-forward -n payments-platform svc/payments-service 8080:8080
curl http://localhost:8080/swagger-ui.html
```

### Ledger Service
```bash
# Port-forward (ClusterIP only)
kubectl port-forward -n payments-platform svc/ledger-service 8081:8081

# Then access
curl http://localhost:8081/swagger-ui.html
```

## Verify Deployment

```bash
# Check all pods
kubectl get pods -n payments-platform

# Check services
kubectl get services -n payments-platform

# View logs
kubectl logs -f deployment/ledger-service -n payments-platform
kubectl logs -f deployment/payments-service -n payments-platform
```

## Testing

### Test Payments Service

```bash
# Via Ingress
curl http://payments.local/swagger-ui.html

# Create a payment (example)
curl -X POST http://payments.local/payments \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "550e8400-e29b-41d4-a716-446655440000",
    "amountCents": 1000,
    "currency": "USD"
  }'

# Or via port-forward
kubectl port-forward -n payments-platform svc/payments-service 8080:8080
curl -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "550e8400-e29b-41d4-a716-446655440000",
    "amountCents": 1000,
    "currency": "USD"
  }'
```

### Test Ledger Service

```bash
# Port-forward first
kubectl port-forward -n payments-platform svc/ledger-service 8081:8081

# In another terminal, test
curl http://localhost:8081/swagger-ui.html
```

## Troubleshooting

### Pods Not Starting

```bash
# Check pod status
kubectl describe pod <pod-name> -n payments-platform

# Check logs
kubectl logs <pod-name> -n payments-platform
```

### Database Connection Issues

```bash
# Check PostgreSQL
kubectl get pods -l app=postgres -n payments-platform
kubectl logs -l app=postgres -n payments-platform

# Test connection
kubectl run -it --rm debug --image=postgres:15-alpine --restart=Never -n payments-platform -- psql -h postgres-service -U postgres
```

### Rebuild and Redeploy

```bash
# Rebuild images
./scripts/deploy.sh build

# Redeploy
./scripts/deploy.sh ledger
./scripts/deploy.sh payments
```

## Cleanup

```bash
# Remove everything
kubectl delete namespace payments-platform

# Or remove specific service
kubectl delete -f helm/payments/
kubectl delete -f helm/ledger/
kubectl delete -f helm/postgres/
```

