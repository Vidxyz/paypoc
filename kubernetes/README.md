# Kubernetes Deployment Guide

This directory contains Kubernetes manifests for deploying the Payments Platform to a minikube cluster.

## Structure

```
helm/
├── ledger/
│   ├── namespace.yaml      # Namespace definition
│   ├── configmap.yaml     # Non-sensitive configuration
│   ├── secret.yaml        # Sensitive data (DB passwords, tokens)
│   ├── deployment.yaml    # Ledger service deployment
│   └── service.yaml       # Ledger service ClusterIP
├── payments/
│   ├── configmap.yaml     # Non-sensitive configuration
│   ├── secret.yaml        # Sensitive data (DB passwords)
│   ├── deployment.yaml    # Payments service deployment
│   ├── service.yaml       # Payments service ClusterIP
│   └── ingress.yaml       # Ingress for Payments service
└── postgres/              # Note: PostgreSQL is now deployed via Terraform
    ├── deployment.yaml    # (Legacy - not used, kept for reference)
    ├── service.yaml       # (Legacy - not used, kept for reference)
    └── init-db.yaml       # (Legacy - not used, kept for reference)
```

## Prerequisites

1. **Minikube** running and configured
   ```bash
   minikube start
   minikube addons enable ingress  # Required for Ingress
   ```

2. **kubectl** configured to use minikube
   ```bash
   kubectl config use-context minikube
   ```

3. **Docker** (for building images)

4. **Kafka and PostgreSQL** deployed (via Terraform in `infra/minikube/`)
   ```bash
   cd infra/minikube
   terraform init
   terraform apply
   ```

## Quick Start

### Automated Deployment

Use the deployment script for a complete deployment:

```bash
./scripts/deploy.sh
```

This will:
1. Build Docker images for both services
2. Load images into minikube
3. Create namespace
4. Deploy PostgreSQL
5. Deploy Ledger Service
6. Deploy Payments Service
7. Wait for all services to be ready

### Manual Deployment

#### 1. Build and Load Images

```bash
# Build images
docker build -t ledger-service:latest -f scripts/Dockerfile.ledger .
docker build -t payments-service:latest -f scripts/Dockerfile.payments .

# Load into minikube
minikube image load ledger-service:latest
minikube image load payments-service:latest
```

#### 2. Deploy PostgreSQL (via Terraform)

**Note:** PostgreSQL is now deployed via Terraform. If not already deployed:

```bash
cd infra/minikube
terraform apply
```

This will deploy PostgreSQL using the Bitnami Helm chart with:
- Databases: `ledger_db` and `payments_db`
- User: `ledger_user` for `ledger_db`
- Service: `postgres-service.payments-platform.svc.cluster.local:5432`

**Verify PostgreSQL is running:**
```bash
kubectl get pods -l app.kubernetes.io/name=postgresql -n payments-platform
```

#### 3. Deploy Ledger Service

```bash
kubectl apply -f helm/ledger/namespace.yaml
kubectl apply -f helm/ledger/configmap.yaml
kubectl apply -f helm/ledger/secret.yaml
kubectl apply -f helm/ledger/deployment.yaml
kubectl apply -f helm/ledger/service.yaml

# Wait for deployment
kubectl wait --for=condition=available deployment/ledger-service -n payments-platform --timeout=300s
```

#### 4. Deploy Payments Service

```bash
kubectl apply -f helm/payments/configmap.yaml
kubectl apply -f helm/payments/secret.yaml
kubectl apply -f helm/payments/deployment.yaml
kubectl apply -f helm/payments/service.yaml
kubectl apply -f helm/payments/ingress.yaml

# Wait for deployment
kubectl wait --for=condition=available deployment/payments-service -n payments-platform --timeout=300s
```

**Note:** The Payments Service is exposed via Ingress. Ensure NGINX Ingress Controller is installed:
```bash
minikube addons enable ingress
```

## Configuration

### Environment Variables

Services are configured via:
- **ConfigMaps**: Non-sensitive configuration (application.yml)
- **Secrets**: Sensitive data (DB passwords, API tokens)

### Updating Configuration

#### Update ConfigMap

```bash
# Edit the configmap
kubectl edit configmap ledger-config -n payments-platform
kubectl edit configmap payments-config -n payments-platform

# Restart deployments to pick up changes
kubectl rollout restart deployment/ledger-service -n payments-platform
kubectl rollout restart deployment/payments-service -n payments-platform
```

#### Update Secrets

```bash
# Edit the secret
kubectl edit secret ledger-secrets -n payments-platform
kubectl edit secret payments-secrets -n payments-platform

# Restart deployments
kubectl rollout restart deployment/ledger-service -n payments-platform
kubectl rollout restart deployment/payments-service -n payments-platform
```

## Accessing Services

### Payments Service

Exposed via Ingress:

1. **Enable Ingress in minikube:**
   ```bash
   minikube addons enable ingress
   ```

2. **Add to /etc/hosts (or use port-forward):**
   ```bash
   echo "$(minikube ip) payments.local" | sudo tee -a /etc/hosts
   ```

3. **Access via Ingress:**
   ```bash
   curl http://payments.local/swagger-ui.html
   ```

   Or access directly:
   ```bash
   http://payments.local
   ```

4. **Alternative: Port-forward (if Ingress not configured):**
   ```bash
   kubectl port-forward -n payments-platform svc/payments-service 8080:8080
   ```
   Then access at: `http://localhost:8080`

### Ledger Service

Exposed via ClusterIP (internal only). Use port-forward:

```bash
kubectl port-forward -n payments-platform svc/ledger-service 8081:8081
```

Then access at: `http://localhost:8081`

### Swagger UI

- **Payments**: http://payments.local/swagger-ui.html (via Ingress)
- **Ledger**: http://localhost:8081/swagger-ui.html (after port-forward)

## Monitoring

### Check Pod Status

```bash
kubectl get pods -n payments-platform
```

### Check Service Status

```bash
kubectl get services -n payments-platform
```

### View Logs

```bash
# Ledger Service
kubectl logs -f deployment/ledger-service -n payments-platform

# Payments Service
kubectl logs -f deployment/payments-service -n payments-platform

# PostgreSQL
kubectl logs -f statefulset/postgres -n payments-platform
```

### Describe Resources

```bash
kubectl describe pod <pod-name> -n payments-platform
kubectl describe deployment/ledger-service -n payments-platform
kubectl describe deployment/payments-service -n payments-platform
```

## Troubleshooting

### Pods Not Starting

1. Check pod status:
   ```bash
   kubectl get pods -n payments-platform
   kubectl describe pod <pod-name> -n payments-platform
   ```

2. Check logs:
   ```bash
   kubectl logs <pod-name> -n payments-platform
   ```

3. Common issues:
   - **ImagePullBackOff**: Image not found - ensure images are loaded into minikube
   - **CrashLoopBackOff**: Check application logs for errors
   - **Pending**: Check resource constraints

### Database Connection Issues

1. Verify PostgreSQL is running:
   ```bash
   kubectl get pods -l app=postgres -n payments-platform
   ```

2. Check PostgreSQL logs:
   ```bash
   kubectl logs -l app=postgres -n payments-platform
   ```

3. Test connection from a pod:
   ```bash
   kubectl run -it --rm debug --image=postgres:15-alpine --restart=Never -n payments-platform -- psql -h postgres-service -U postgres
   ```

### Service Communication Issues

1. Verify services are running:
   ```bash
   kubectl get endpoints -n payments-platform
   ```

2. Test connectivity from within cluster:
   ```bash
   kubectl run -it --rm debug --image=curlimages/curl --restart=Never -n payments-platform -- curl http://ledger-service:8081/actuator/health
   ```

## Cleanup

### Remove All Resources

```bash
kubectl delete namespace payments-platform
```

### Remove Specific Service

```bash
# Remove Payments Service
kubectl delete -f helm/payments/

# Remove Ledger Service
kubectl delete -f helm/ledger/

# Remove PostgreSQL
kubectl delete -f helm/postgres/
```

## Development Workflow

### 1. Make Code Changes

Edit code in `services/ledger/` or `services/payments/`

### 2. Rebuild and Redeploy

```bash
# Rebuild images
./scripts/deploy.sh build

# Redeploy specific service
./scripts/deploy.sh ledger
./scripts/deploy.sh payments
```

### 3. Test Changes

```bash
# Check status
./scripts/deploy.sh status

# View logs
kubectl logs -f deployment/payments-service -n payments-platform
```

## Production Considerations

⚠️ **These manifests are for development/minikube only!**

For production, consider:
- Use proper image registry (not `latest` tags)
- Use StatefulSets for databases with persistent volumes
- Add resource limits and requests
- Configure proper health checks
- Add network policies
- Use secrets management (e.g., Vault, Sealed Secrets)
- Configure autoscaling (HPA)
- Add monitoring and alerting
- Use Ingress instead of NodePort
- Configure TLS/SSL
- Add backup strategies for databases

