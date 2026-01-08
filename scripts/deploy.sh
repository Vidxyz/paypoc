#!/bin/bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
NAMESPACE="payments-platform"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
K8S_DIR="$PROJECT_ROOT/kubernetes"

# Functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_prerequisites() {
    log_info "Checking prerequisites..."
    
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed"
        exit 1
    fi
    
    if ! command -v docker &> /dev/null; then
        log_error "docker is not installed"
        exit 1
    fi
    
    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to Kubernetes cluster"
        exit 1
    fi
    
    log_info "Prerequisites check passed"
}

build_images() {
    log_info "Building Docker images..."
    
    # Build ledger service
    log_info "Building ledger-service image..."
    cd "$PROJECT_ROOT"
    docker build -t ledger-service:latest -f scripts/Dockerfile.ledger .
    
    # Build payments service
    log_info "Building payments-service image..."
    docker build -t payments-service:latest -f scripts/Dockerfile.payments .
    
    # Build frontend
    log_info "Building frontend image..."
    cd "$PROJECT_ROOT/services/frontend"
    
    # Use environment variable or fallback to test key
    # Test key is safe to commit, live keys should come from CI/CD secrets
    local stripe_key="${STRIPE_PUBLISHABLE_KEY:-missing_stripe_publishable_key}"
    
    # Log which key type is being used (masked for security)
    if [[ "$stripe_key" == pk_test_* ]]; then
        log_info "Using Stripe TEST publishable key: ${stripe_key:0:20}..."
    elif [[ "$stripe_key" == pk_live_* ]]; then
        log_info "Using Stripe LIVE publishable key: ${stripe_key:0:20}..."
    else
        log_warn "Unknown Stripe key format, using as-is: ${stripe_key:0:20}..."
    fi
    
    docker build \
      --build-arg VITE_STRIPE_PUBLISHABLE_KEY="$stripe_key" \
      -t frontend:latest .
    
    # Load images into minikube
    log_info "Loading images into minikube..."
    minikube image load ledger-service:latest || true
    minikube image load payments-service:latest || true
    minikube image load frontend:latest || true
    
    log_info "Images built and loaded"
}

create_namespace() {
    log_info "Creating namespace: $NAMESPACE"
    kubectl apply -f "$K8S_DIR/ledger/namespace.yaml" || kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
}

deploy_postgres() {
    log_info "PostgreSQL is deployed via Terraform in infra/minikube/"
    log_info "Skipping PostgreSQL deployment in script - use 'terraform apply' in infra/minikube/"
    log_info "Checking if PostgreSQL is running..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=postgresql -n "$NAMESPACE" --timeout=60s || log_warn "PostgreSQL may not be ready yet. Run 'terraform apply' in infra/minikube/ to deploy it."
}

deploy_ledger() {
    log_info "Deploying Ledger Service..."
    kubectl apply -f "$K8S_DIR/ledger/configmap.yaml"
    kubectl apply -f "$K8S_DIR/ledger/secret.yaml"
    # kubectl delete -f "$K8S_DIR/ledger/deployment.yaml"
    kubectl apply -f "$K8S_DIR/ledger/deployment.yaml"
    kubectl apply -f "$K8S_DIR/ledger/service.yaml"
    
    # log_info "Waiting for Ledger Service to be ready..."
    # kubectl wait --for=condition=available deployment/ledger-service -n "$NAMESPACE" --timeout=300s || true
}

deploy_ingress() {
    log_info "Deploying NGINX Ingress Controller..."
    
    # Check if ingress controller already exists
    if ! kubectl get deployment ingress-nginx-controller -n ingress-nginx &>/dev/null; then
        log_info "Installing NGINX Ingress Controller..."
        kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml
        
        log_info "Waiting for Ingress Controller to be ready..."
        kubectl wait --namespace ingress-nginx \
          --for=condition=ready pod \
          --selector=app.kubernetes.io/component=controller \
          --timeout=300s || log_warn "Ingress controller may not be ready yet"
    else
        log_info "NGINX Ingress Controller already installed"
    fi
}

deploy_payments() {
    log_info "Deploying Payments Service..."
    kubectl apply -f "$K8S_DIR/payments/configmap.yaml"
    kubectl apply -f "$K8S_DIR/payments/secret.yaml"
    # kubectl delete -f "$K8S_DIR/payments/deployment.yaml"
    kubectl apply -f "$K8S_DIR/payments/deployment.yaml"
    kubectl apply -f "$K8S_DIR/payments/service.yaml"
    kubectl apply -f "$K8S_DIR/payments/ingress.yaml"
    
    # log_info "Waiting for Payments Service to be ready..."
    # kubectl wait --for=condition=available deployment/payments-service -n "$NAMESPACE" --timeout=300s || true
}

deploy_frontend() {
    log_info "Deploying Frontend..."
    kubectl apply -f "$K8S_DIR/frontend/namespace.yaml"
    kubectl apply -f "$K8S_DIR/frontend/configmap.yaml"
    # kubectl delete -f "$K8S_DIR/frontend/deployment.yaml"
    kubectl apply -f "$K8S_DIR/frontend/deployment.yaml"
    kubectl apply -f "$K8S_DIR/frontend/service.yaml"
    kubectl apply -f "$K8S_DIR/frontend/ingress.yaml"
    
    # log_info "Waiting for Frontend to be ready..."
    # kubectl wait --for=condition=available deployment/frontend -n "$NAMESPACE" --timeout=300s || true
}

wait_for_services() {
    log_info "Waiting for all services to be ready..."
    
    # Wait for PostgreSQL
    log_info "Waiting for PostgreSQL..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=postgresql -n "$NAMESPACE" --timeout=300s || log_warn "PostgreSQL not ready yet"
    
    # Wait for Ledger Service
    log_info "Waiting for Ledger Service..."
    kubectl wait --for=condition=available deployment/ledger-service -n "$NAMESPACE" --timeout=300s || log_warn "Ledger Service not ready yet"
    
    # Wait for Payments Service
    log_info "Waiting for Payments Service..."
    kubectl wait --for=condition=available deployment/payments-service -n "$NAMESPACE" --timeout=300s || log_warn "Payments Service not ready yet"
    
    # Wait for Frontend
    log_info "Waiting for Frontend..."
    kubectl wait --for=condition=available deployment/frontend -n "$NAMESPACE" --timeout=300s || log_warn "Frontend not ready yet"
    
    log_info "Services deployment complete"
}

show_status() {
    log_info "Deployment Status:"
    echo ""
    kubectl get pods -n "$NAMESPACE"
    echo ""
    kubectl get services -n "$NAMESPACE"
    echo ""
    kubectl get ingress -n "$NAMESPACE"
    echo ""
    
    log_info "Access URLs:"
    MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "localhost")
    INGRESS_IP=$(kubectl get ingress frontend-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    
    if [ -n "$INGRESS_IP" ]; then
        echo "  Frontend (Ingress): http://buyit.local"
    else
        echo "  Frontend (Ingress): http://buyit.local"
        echo "    Add to /etc/hosts: $MINIKUBE_IP buyit.local"
        echo "    Or use port-forward: kubectl port-forward -n $NAMESPACE svc/frontend 3000:80"
    fi
    
    INGRESS_IP_PAYMENTS=$(kubectl get ingress payments-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    if [ -n "$INGRESS_IP_PAYMENTS" ]; then
        echo "  Payments Service (Ingress): http://$INGRESS_IP_PAYMENTS"
    else
        echo "  Payments Service (Ingress): http://payments.local (add to /etc/hosts: $MINIKUBE_IP payments.local)"
        echo "    Or use port-forward: kubectl port-forward -n $NAMESPACE svc/payments-service 8080:8080"
    fi
    
    echo "  Ledger Service: http://$(kubectl get svc ledger-service -n $NAMESPACE -o jsonpath='{.spec.clusterIP}'):8081 (ClusterIP only)"
    echo ""
    log_info "To port-forward services:"
    echo "  Frontend: kubectl port-forward -n $NAMESPACE svc/frontend 3000:80"
    echo "  Payments: kubectl port-forward -n $NAMESPACE svc/payments-service 8080:8080"
    echo "  Ledger: kubectl port-forward -n $NAMESPACE svc/ledger-service 8081:8081"
}

# Main execution
main() {
    log_info "Starting deployment to minikube..."
    
    check_prerequisites
    build_images
    create_namespace
    deploy_ingress
    deploy_postgres
    sleep 10  # Give PostgreSQL time to initialize
    deploy_ledger
    sleep 10  # Give Ledger time to initialize
    deploy_payments
    sleep 10  # Give Payments time to initialize
    deploy_frontend
    wait_for_services
    show_status
    
    log_info "Deployment complete!"
}

# Parse arguments
case "${1:-}" in
    build)
        check_prerequisites
        build_images
        ;;
    ingress)
        check_prerequisites
        deploy_ingress
        ;;
    postgres)
        log_warn "PostgreSQL is now deployed via Terraform. Use 'terraform apply' in infra/minikube/"
        ;;
    ledger)
        check_prerequisites
        create_namespace
        deploy_ledger
        ;;
    payments)
        check_prerequisites
        create_namespace
        deploy_payments
        ;;
    frontend)
        check_prerequisites
        create_namespace
        deploy_frontend
        ;;
    status)
        show_status
        ;;
    *)
        main
        ;;
esac

