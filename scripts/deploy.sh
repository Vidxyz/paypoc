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
    log_info "Deleting deployments..."
    
    kubectl delete -f "$K8S_DIR/ledger/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/payments/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/frontend/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/admin-console/deployment.yaml" 2>/dev/null || true

    log_info "Building Docker images in parallel..."
    
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
    
    # Build all images in parallel using background processes
    log_info "Building ledger-service image..."
    (cd "$PROJECT_ROOT" && docker build -t ledger-service:latest -f scripts/Dockerfile.ledger .) &
    LEDGER_PID=$!
    
    log_info "Building payments-service image..."
    (cd "$PROJECT_ROOT" && docker build -t payments-service:latest -f scripts/Dockerfile.payments .) &
    PAYMENTS_PID=$!
    
    log_info "Building frontend image..."
    (cd "$PROJECT_ROOT/services/frontend" && docker build --build-arg VITE_STRIPE_PUBLISHABLE_KEY="$stripe_key" -t frontend:latest .) &
    FRONTEND_PID=$!
    
    log_info "Building admin-console image..."
    (cd "$PROJECT_ROOT/services/admin-console" && docker build -t admin-console:latest .) &
    ADMIN_CONSOLE_PID=$!
    
    # Wait for all builds to complete
    log_info "Waiting for all image builds to complete..."
    wait $LEDGER_PID || { log_error "Ledger image build failed"; exit 1; }
    log_info "Ledger image built successfully"
    
    wait $PAYMENTS_PID || { log_error "Payments image build failed"; exit 1; }
    log_info "Payments image built successfully"
    
    wait $FRONTEND_PID || { log_error "Frontend image build failed"; exit 1; }
    log_info "Frontend image built successfully"
    
    wait $ADMIN_CONSOLE_PID || { log_error "Admin-console image build failed"; exit 1; }
    log_info "Admin-console image built successfully"
    
    # Load images into minikube in parallel
    log_info "Loading images into minikube in parallel..."
    minikube image load ledger-service:latest &
    minikube image load payments-service:latest &
    minikube image load frontend:latest &
    minikube image load admin-console:latest &
    
    # Wait for all image loads to complete
    wait
    
    log_info "All images built and loaded"
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
    kubectl apply -f "$K8S_DIR/ledger/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/ledger/secret.yaml" &
    kubectl apply -f "$K8S_DIR/ledger/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/ledger/service.yaml" &
    wait
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
    kubectl apply -f "$K8S_DIR/payments/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/payments/secret.yaml" &
    kubectl apply -f "$K8S_DIR/payments/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/payments/service.yaml" &
    kubectl apply -f "$K8S_DIR/payments/ingress.yaml" &
    wait
}

deploy_frontend() {
    log_info "Deploying Frontend..."
    kubectl apply -f "$K8S_DIR/frontend/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/frontend/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/frontend/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/frontend/service.yaml" &
    kubectl apply -f "$K8S_DIR/frontend/ingress.yaml" &
    wait
}

deploy_admin_console() {
    log_info "Deploying Admin Console..."
    kubectl apply -f "$K8S_DIR/admin-console/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/admin-console/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/admin-console/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/admin-console/service.yaml" &
    kubectl apply -f "$K8S_DIR/admin-console/ingress.yaml" &
    wait
}

wait_for_services() {
    log_info "Waiting for all services to be ready (in parallel)..."
    
    # Wait for all services in parallel using background processes
    log_info "Waiting for PostgreSQL..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=postgresql -n "$NAMESPACE" --timeout=300s || log_warn "PostgreSQL not ready yet" &
    POSTGRES_PID=$!
    
    log_info "Waiting for Ledger Service..."
    kubectl wait --for=condition=available deployment/ledger-service -n "$NAMESPACE" --timeout=300s || log_warn "Ledger Service not ready yet" &
    LEDGER_PID=$!
    
    log_info "Waiting for Payments Service..."
    kubectl wait --for=condition=available deployment/payments-service -n "$NAMESPACE" --timeout=300s || log_warn "Payments Service not ready yet" &
    PAYMENTS_PID=$!
    
    log_info "Waiting for Frontend..."
    kubectl wait --for=condition=available deployment/frontend -n "$NAMESPACE" --timeout=300s || log_warn "Frontend not ready yet" &
    FRONTEND_PID=$!
    
    log_info "Waiting for Admin Console..."
    kubectl wait --for=condition=available deployment/admin-console -n "$NAMESPACE" --timeout=300s || log_warn "Admin Console not ready yet" &
    ADMIN_CONSOLE_PID=$!
    
    # Wait for all services to be ready
    wait $POSTGRES_PID
    wait $LEDGER_PID
    wait $PAYMENTS_PID
    wait $FRONTEND_PID
    wait $ADMIN_CONSOLE_PID
    
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
    
    INGRESS_IP_ADMIN=$(kubectl get ingress admin-console-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    if [ -n "$INGRESS_IP_ADMIN" ]; then
        echo "  Admin Console (Ingress): http://admin.local"
    else
        echo "  Admin Console (Ingress): http://admin.local"
        echo "    Add to /etc/hosts: $MINIKUBE_IP admin.local"
        echo "    Or use port-forward: kubectl port-forward -n $NAMESPACE svc/admin-console 3001:80"
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
    echo "  Admin Console: kubectl port-forward -n $NAMESPACE svc/admin-console 3001:80"
    echo "  Payments: kubectl port-forward -n $NAMESPACE svc/payments-service 8080:8080"
    echo "  Ledger: kubectl port-forward -n $NAMESPACE svc/ledger-service 8081:8081"
}

# Main execution
main() {
    log_info "Starting deployment to minikube..."
    
    check_prerequisites
    build_images
    create_namespace
    
    # Deploy ingress and check postgres in parallel (postgres check is non-blocking)
    log_info "Setting up infrastructure..."
    deploy_ingress &
    INGRESS_PID=$!
    deploy_postgres &
    POSTGRES_CHECK_PID=$!
    
    # Wait for ingress to be ready before deploying services
    wait $INGRESS_PID
    
    # Deploy all services in parallel (they don't depend on each other)
    log_info "Deploying all services in parallel..."
    deploy_ledger &
    deploy_payments &
    deploy_frontend &
    deploy_admin_console &
    
    # Wait for postgres check to complete (non-blocking, just logs warnings if not ready)
    wait $POSTGRES_CHECK_PID
    
    # Wait for all service deployments to complete
    wait
    
    # Now wait for all services to be ready (in parallel)
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
    admin-console)
        check_prerequisites
        create_namespace
        deploy_admin_console
        ;;
    status)
        show_status
        ;;
    *)
        main
        ;;
esac

