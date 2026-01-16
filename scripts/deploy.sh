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

# Functions (define before using)
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Load environment variables from .env file if it exists
ENV_FILE="$SCRIPT_DIR/.env"
if [[ -f "$ENV_FILE" ]]; then
    log_info "Loading environment variables from $ENV_FILE"
    # Source the .env file, but don't fail if it doesn't exist
    set -a  # Automatically export all variables
    source "$ENV_FILE" 2>/dev/null || true
    set +a  # Disable automatic export
else
    log_warn ".env file not found at $ENV_FILE"
    log_warn "Create $ENV_FILE from $ENV_FILE.example and set your environment variables"
    log_warn "Continuing with environment variables from current shell..."
fi

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

check_auth0_env_vars() {
    log_info "Checking Auth0 environment variables..."
    
    local missing_vars=()
    
    # Check shared Auth0 domain
    if [[ -z "${AUTH0_DOMAIN:-}" ]]; then
        missing_vars+=("AUTH0_DOMAIN")
    fi
    
    # Check frontend Auth0 client ID
    if [[ -z "${AUTH0_FRONTEND_CLIENT_ID:-}" ]]; then
        missing_vars+=("AUTH0_FRONTEND_CLIENT_ID")
    fi
    
    # Check admin console Auth0 client ID
    if [[ -z "${AUTH0_ADMIN_CLIENT_ID:-}" ]]; then
        missing_vars+=("AUTH0_ADMIN_CLIENT_ID")
    fi
    
    # Check seller console Auth0 client ID
    if [[ -z "${AUTH0_SELLER_CLIENT_ID:-}" ]]; then
        missing_vars+=("AUTH0_SELLER_CLIENT_ID")
    fi
    
    if [[ ${#missing_vars[@]} -gt 0 ]]; then
        log_error "Missing required Auth0 environment variables:"
        for var in "${missing_vars[@]}"; do
            log_error "  - $var"
        done
        log_error ""
        log_error "Please set these environment variables in $ENV_FILE or export them:"
        log_error "  AUTH0_DOMAIN=your-tenant.auth0.com"
        log_error "  AUTH0_FRONTEND_CLIENT_ID=your-frontend-client-id"
        log_error "  AUTH0_ADMIN_CLIENT_ID=your-admin-console-client-id"
        log_error "  AUTH0_SELLER_CLIENT_ID=your-seller-console-client-id"
        log_error ""
        log_error "Optional variables (with defaults):"
        log_error "  AUTH0_FRONTEND_REDIRECT_URI=https://buyit.local  # Defaults to window.location.origin"
        log_error "  AUTH0_ADMIN_REDIRECT_URI=https://admin.local      # Defaults to window.location.origin"
        log_error "  AUTH0_SELLER_REDIRECT_URI=https://seller.local     # Defaults to window.location.origin"
        log_error "  AUTH0_FRONTEND_AUDIENCE=your-api-audience         # Optional, for API access"
        log_error "  AUTH0_ADMIN_AUDIENCE=your-api-audience            # Optional, for API access"
        log_error "  AUTH0_SELLER_AUDIENCE=your-api-audience           # Optional, for API access"
        log_error ""
        log_warn "Building frontend/admin-console/seller-console without Auth0 variables will result in a 401 error during login!"
        read -p "Continue anyway? (y/N) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_error "Build cancelled. Please set the required environment variables and try again."
            exit 1
        fi
    else
        log_info "Auth0 environment variables check passed:"
        log_info "  AUTH0_DOMAIN: ${AUTH0_DOMAIN:0:30}..."
        log_info "  AUTH0_FRONTEND_CLIENT_ID: ${AUTH0_FRONTEND_CLIENT_ID:0:20}..."
        log_info "  AUTH0_ADMIN_CLIENT_ID: ${AUTH0_ADMIN_CLIENT_ID:0:20}..."
        log_info "  AUTH0_SELLER_CLIENT_ID: ${AUTH0_SELLER_CLIENT_ID:0:20}..."
        if [[ -n "${AUTH0_FRONTEND_REDIRECT_URI:-}" ]]; then
            log_info "  AUTH0_FRONTEND_REDIRECT_URI: $AUTH0_FRONTEND_REDIRECT_URI"
        else
            log_info "  AUTH0_FRONTEND_REDIRECT_URI: (not set, will use window.location.origin)"
        fi
        if [[ -n "${AUTH0_ADMIN_REDIRECT_URI:-}" ]]; then
            log_info "  AUTH0_ADMIN_REDIRECT_URI: $AUTH0_ADMIN_REDIRECT_URI"
        else
            log_info "  AUTH0_ADMIN_REDIRECT_URI: (not set, will use window.location.origin)"
        fi
        if [[ -n "${AUTH0_SELLER_REDIRECT_URI:-}" ]]; then
            log_info "  AUTH0_SELLER_REDIRECT_URI: $AUTH0_SELLER_REDIRECT_URI"
        else
            log_info "  AUTH0_SELLER_REDIRECT_URI: (not set, will use window.location.origin)"
        fi
        if [[ -n "${AUTH0_FRONTEND_AUDIENCE:-}" ]]; then
            log_info "  AUTH0_FRONTEND_AUDIENCE: $AUTH0_FRONTEND_AUDIENCE"
        else
            log_info "  AUTH0_FRONTEND_AUDIENCE: (not set, optional)"
        fi
        if [[ -n "${AUTH0_ADMIN_AUDIENCE:-}" ]]; then
            log_info "  AUTH0_ADMIN_AUDIENCE: $AUTH0_ADMIN_AUDIENCE"
        else
            log_info "  AUTH0_ADMIN_AUDIENCE: (not set, optional)"
        fi
        if [[ -n "${AUTH0_SELLER_AUDIENCE:-}" ]]; then
            log_info "  AUTH0_SELLER_AUDIENCE: $AUTH0_SELLER_AUDIENCE"
        else
            log_info "  AUTH0_SELLER_AUDIENCE: (not set, optional)"
        fi
    fi
}

build_images() {
    local services=("$@")
    
    # If no services provided, default to "all"
    if [[ ${#services[@]} -eq 0 ]]; then
        services=("all")
    fi
    
    # If "all" is specified, build all services
    if [[ "${services[0]}" == "all" ]]; then
        build_all_images
        return
    fi
    
    # Build multiple services in parallel
    if [[ ${#services[@]} -gt 1 ]]; then
        log_info "Building Docker images for services: ${services[*]} (in parallel)..."
        
        # Check Auth0 environment variables if any frontend service is being built
        local has_frontend=false
        for service in "${services[@]}"; do
            if [[ "$service" == "frontend" || "$service" == "admin-console" || "$service" == "seller-console" ]]; then
                has_frontend=true
                break
            fi
        done
        
        if [[ "$has_frontend" == true ]]; then
            check_auth0_env_vars
        fi
        
        # Build all services in parallel using background processes
        local pids=()
        for service in "${services[@]}"; do
            build_single_image "$service" &
            pids+=($!)
        done
        
        # Wait for all builds to complete
        local failed=false
        local i=0
        for pid in "${pids[@]}"; do
            wait "$pid" || {
                log_error "Image build failed for ${services[$i]}"
                failed=true
            }
            ((i++))
        done
        
        if [[ "$failed" == true ]]; then
            exit 1
        fi
        
        log_info "All images built successfully"
        return
    fi
    
    # Build single service
    log_info "Building Docker image for ${services[0]} service..."
    build_single_image "${services[0]}"
}

build_all_images() {
    
    log_info "Deleting deployments..."
    
    kubectl delete -f "$K8S_DIR/ledger/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/payments/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/frontend/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/admin-console/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/seller-console/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/user/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/catalog/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/inventory/deployment.yaml" 2>/dev/null || true
    kubectl delete -f "$K8S_DIR/cart/deployment.yaml" 2>/dev/null || true

    log_info "Building Docker images in parallel..."
    
    # Check Auth0 environment variables before building frontend
    check_auth0_env_vars
    
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
    
    # Set environment variables for frontend build
    local stripe_key="${STRIPE_PUBLISHABLE_KEY:-missing_stripe_publishable_key}"
    local auth0_domain="${AUTH0_DOMAIN:-your-tenant.auth0.com}"
    local auth0_frontend_client_id="${AUTH0_FRONTEND_CLIENT_ID:-}"
    local auth0_frontend_redirect_uri="${AUTH0_FRONTEND_REDIRECT_URI:-}"
    local auth0_frontend_audience="${AUTH0_FRONTEND_AUDIENCE:-}"
    
    log_info "Building frontend image..."
    (cd "$PROJECT_ROOT/services/frontend" && docker build \
        --build-arg VITE_STRIPE_PUBLISHABLE_KEY="$stripe_key" \
        --build-arg VITE_AUTH0_DOMAIN="$auth0_domain" \
        --build-arg VITE_AUTH0_CLIENT_ID="$auth0_frontend_client_id" \
        --build-arg VITE_AUTH0_REDIRECT_URI="$auth0_frontend_redirect_uri" \
        --build-arg VITE_AUTH0_AUDIENCE="$auth0_frontend_audience" \
        -t frontend:latest .) &
    FRONTEND_PID=$!
    
    log_info "Building admin-console image..."
    # Use separate Auth0 client ID for admin console
    local auth0_domain_admin="${AUTH0_DOMAIN:-your-tenant.auth0.com}"
    local auth0_admin_client_id="${AUTH0_ADMIN_CLIENT_ID:-}"
    local auth0_admin_redirect_uri="${AUTH0_ADMIN_REDIRECT_URI:-}"
    local auth0_admin_audience="${AUTH0_ADMIN_AUDIENCE:-}"
    (cd "$PROJECT_ROOT/services/admin-console" && docker build \
        --build-arg VITE_AUTH0_DOMAIN="$auth0_domain_admin" \
        --build-arg VITE_AUTH0_CLIENT_ID="$auth0_admin_client_id" \
        --build-arg VITE_AUTH0_REDIRECT_URI="$auth0_admin_redirect_uri" \
        --build-arg VITE_AUTH0_AUDIENCE="$auth0_admin_audience" \
        -t admin-console:latest .) &
    ADMIN_CONSOLE_PID=$!
    
    log_info "Building seller-console image..."
    # Use separate Auth0 client ID for seller console
    local auth0_domain_seller="${AUTH0_DOMAIN:-your-tenant.auth0.com}"
    local auth0_seller_client_id="${AUTH0_SELLER_CLIENT_ID:-}"
    local auth0_seller_redirect_uri="${AUTH0_SELLER_REDIRECT_URI:-}"
    local auth0_seller_audience="${AUTH0_SELLER_AUDIENCE:-}"
    (cd "$PROJECT_ROOT/services/seller-console" && docker build \
        --build-arg VITE_AUTH0_DOMAIN="$auth0_domain_seller" \
        --build-arg VITE_AUTH0_CLIENT_ID="$auth0_seller_client_id" \
        --build-arg VITE_AUTH0_REDIRECT_URI="$auth0_seller_redirect_uri" \
        --build-arg VITE_AUTH0_AUDIENCE="$auth0_seller_audience" \
        -t seller-console:latest .) &
    SELLER_CONSOLE_PID=$!
    
    log_info "Building user-service image..."
    (cd "$PROJECT_ROOT/services/user" && docker build -t user-service:latest .) &
    USER_PID=$!
    
    log_info "Building catalog-service image..."
    (cd "$PROJECT_ROOT/services/catalog" && docker build -t catalog-service:latest .) &
    CATALOG_PID=$!
    
    log_info "Building inventory-service image..."
    (cd "$PROJECT_ROOT" && docker build -t inventory-service:latest -f services/inventory/Dockerfile services/inventory) &
    INVENTORY_PID=$!
    
    log_info "Building cart-service image..."
    (cd "$PROJECT_ROOT/services/cart" && docker build -t cart-service:latest .) &
    CART_PID=$!
    
    log_info "Building order-service image..."
    (cd "$PROJECT_ROOT/services/order" && docker build -t order-service:latest .) &
    ORDER_PID=$!
    
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
    
    wait $SELLER_CONSOLE_PID || { log_error "Seller-console image build failed"; exit 1; }
    log_info "Seller-console image built successfully"
    
    wait $USER_PID || { log_error "User image build failed"; exit 1; }
    log_info "User image built successfully"
    
    wait $CATALOG_PID || { log_error "Catalog image build failed"; exit 1; }
    log_info "Catalog image built successfully"
    
    wait $INVENTORY_PID || { log_error "Inventory image build failed"; exit 1; }
    log_info "Inventory image built successfully"
    
    wait $CART_PID || { log_error "Cart image build failed"; exit 1; }
    log_info "Cart image built successfully"
    
    wait $ORDER_PID || { log_error "Order image build failed"; exit 1; }
    log_info "Order image built successfully"
    
    # Load images into minikube in parallel
    log_info "Loading images into minikube in parallel..."
    minikube image load ledger-service:latest &
    minikube image load payments-service:latest &
    minikube image load frontend:latest &
    minikube image load admin-console:latest &
    minikube image load seller-console:latest &
    minikube image load user-service:latest &
    minikube image load catalog-service:latest &
    minikube image load inventory-service:latest &
    minikube image load cart-service:latest &
    minikube image load order-service:latest &
    
    # Wait for all image loads to complete
    wait
    
    log_info "All images built and loaded"
}

delete_deployments() {
    local services=("$@")
    
    log_info "Deleting existing deployments for services: ${services[*]}..."
    for service in "${services[@]}"; do
        log_info "Deleting existing $service deployment..."
        case "$service" in
            ledger)
                kubectl delete -f "$K8S_DIR/ledger/deployment.yaml" 2>/dev/null || true
                ;;
            payments)
                kubectl delete -f "$K8S_DIR/payments/deployment.yaml" 2>/dev/null || true
                ;;
            frontend)
                kubectl delete -f "$K8S_DIR/frontend/deployment.yaml" 2>/dev/null || true
                ;;
            admin-console)
                kubectl delete -f "$K8S_DIR/admin-console/deployment.yaml" 2>/dev/null || true
                ;;
            seller-console)
                kubectl delete -f "$K8S_DIR/seller-console/deployment.yaml" 2>/dev/null || true
                ;;
            user)
                kubectl delete -f "$K8S_DIR/user/deployment.yaml" 2>/dev/null || true
                ;;
            catalog)
                kubectl delete -f "$K8S_DIR/catalog/deployment.yaml" 2>/dev/null || true
                ;;
            inventory)
                kubectl delete -f "$K8S_DIR/inventory/deployment.yaml" 2>/dev/null || true
                ;;
            cart)
                kubectl delete -f "$K8S_DIR/cart/deployment.yaml" 2>/dev/null || true
                ;;
            order)
                kubectl delete -f "$K8S_DIR/order/deployment.yaml" 2>/dev/null || true
                ;;
        esac
    done
}

deploy_multiple_services() {
    local services=("$@")
    
    log_info "Deploying services: ${services[*]} (in parallel)..."
    create_namespace
    
    # Deploy all services in parallel
    local pids=()
    for service in "${services[@]}"; do
        case "$service" in
            ledger)
                deploy_ledger &
                pids+=($!)
                ;;
            payments)
                deploy_payments &
                pids+=($!)
                ;;
            frontend)
                deploy_frontend &
                pids+=($!)
                ;;
            admin-console)
                deploy_admin_console &
                pids+=($!)
                ;;
            seller-console)
                deploy_seller_console &
                pids+=($!)
                ;;
            user)
                deploy_user &
                pids+=($!)
                ;;
            catalog)
                deploy_catalog &
                pids+=($!)
                ;;
            inventory)
                deploy_inventory &
                pids+=($!)
                ;;
            cart)
                deploy_cart &
                pids+=($!)
                ;;
            order)
                deploy_order &
                pids+=($!)
                ;;
        esac
    done
    
    # Wait for all deployments to complete
    local failed=false
    for pid in "${pids[@]}"; do
        wait "$pid" || failed=true
    done
    
    if [[ "$failed" == true ]]; then
        log_error "One or more service deployments failed"
        exit 1
    fi
    
    # Wait for all services to be ready
    wait_for_multiple_services "${services[@]}"
}

wait_for_multiple_services() {
    local services=("$@")
    
    log_info "Waiting for services to be ready: ${services[*]} (in parallel)..."
    
    local pids=()
    for service in "${services[@]}"; do
        wait_for_single_service "$service" &
        pids+=($!)
    done
    
    # Wait for all services to be ready
    local failed=false
    for pid in "${pids[@]}"; do
        wait "$pid" || failed=true
    done
    
    if [[ "$failed" == true ]]; then
        log_warn "One or more services may not be ready yet"
    fi
}

build_single_image() {
    local service_name="$1"
    
    case "$service_name" in
        ledger)
            log_info "Building ledger-service image..."
            (cd "$PROJECT_ROOT" && docker build -t ledger-service:latest -f scripts/Dockerfile.ledger .) || { log_error "Ledger image build failed"; exit 1; }
            minikube image load ledger-service:latest
            log_info "Ledger image built and loaded successfully"
            ;;
        payments)
            log_info "Building payments-service image..."
            (cd "$PROJECT_ROOT" && docker build -t payments-service:latest -f scripts/Dockerfile.payments .) || { log_error "Payments image build failed"; exit 1; }
            minikube image load payments-service:latest
            log_info "Payments image built and loaded successfully"
            ;;
        frontend)
            local stripe_key="${STRIPE_PUBLISHABLE_KEY:-missing_stripe_publishable_key}"
            local auth0_domain="${AUTH0_DOMAIN:-your-tenant.auth0.com}"
            local auth0_frontend_client_id="${AUTH0_FRONTEND_CLIENT_ID:-}"
            local auth0_frontend_redirect_uri="${AUTH0_FRONTEND_REDIRECT_URI:-}"
            local auth0_frontend_audience="${AUTH0_FRONTEND_AUDIENCE:-}"
            log_info "Building frontend image..."
            (cd "$PROJECT_ROOT/services/frontend" && docker build \
                --build-arg VITE_STRIPE_PUBLISHABLE_KEY="$stripe_key" \
                --build-arg VITE_AUTH0_DOMAIN="$auth0_domain" \
                --build-arg VITE_AUTH0_CLIENT_ID="$auth0_frontend_client_id" \
                --build-arg VITE_AUTH0_REDIRECT_URI="$auth0_frontend_redirect_uri" \
                --build-arg VITE_AUTH0_AUDIENCE="$auth0_frontend_audience" \
                -t frontend:latest .) || { log_error "Frontend image build failed"; exit 1; }
            minikube image load frontend:latest
            log_info "Frontend image built and loaded successfully"
            ;;
        admin-console)
            # Use separate Auth0 client ID for admin console
            local auth0_domain_admin="${AUTH0_DOMAIN:-your-tenant.auth0.com}"
            local auth0_admin_client_id="${AUTH0_ADMIN_CLIENT_ID:-}"
            local auth0_admin_redirect_uri="${AUTH0_ADMIN_REDIRECT_URI:-}"
            local auth0_admin_audience="${AUTH0_ADMIN_AUDIENCE:-}"
            log_info "Building admin-console image..."
            (cd "$PROJECT_ROOT/services/admin-console" && docker build \
                --build-arg VITE_AUTH0_DOMAIN="$auth0_domain_admin" \
                --build-arg VITE_AUTH0_CLIENT_ID="$auth0_admin_client_id" \
                --build-arg VITE_AUTH0_REDIRECT_URI="$auth0_admin_redirect_uri" \
                --build-arg VITE_AUTH0_AUDIENCE="$auth0_admin_audience" \
                -t admin-console:latest .) || { log_error "Admin-console image build failed"; exit 1; }
            minikube image load admin-console:latest
            log_info "Admin-console image built and loaded successfully"
            ;;
        seller-console)
            # Use separate Auth0 client ID for seller console
            local auth0_domain_seller="${AUTH0_DOMAIN:-your-tenant.auth0.com}"
            local auth0_seller_client_id="${AUTH0_SELLER_CLIENT_ID:-}"
            local auth0_seller_redirect_uri="${AUTH0_SELLER_REDIRECT_URI:-}"
            local auth0_seller_audience="${AUTH0_SELLER_AUDIENCE:-}"
            log_info "Building seller-console image..."
            (cd "$PROJECT_ROOT/services/seller-console" && docker build \
                --build-arg VITE_AUTH0_DOMAIN="$auth0_domain_seller" \
                --build-arg VITE_AUTH0_CLIENT_ID="$auth0_seller_client_id" \
                --build-arg VITE_AUTH0_REDIRECT_URI="$auth0_seller_redirect_uri" \
                --build-arg VITE_AUTH0_AUDIENCE="$auth0_seller_audience" \
                -t seller-console:latest .) || { log_error "Seller-console image build failed"; exit 1; }
            minikube image load seller-console:latest
            log_info "Seller-console image built and loaded successfully"
            ;;
        user)
            log_info "Building user-service image..."
            (cd "$PROJECT_ROOT/services/user" && docker build -t user-service:latest .) || { log_error "User image build failed"; exit 1; }
            minikube image load user-service:latest
            log_info "User image built and loaded successfully"
            ;;
            catalog)
            log_info "Building catalog-service image..."
            (cd "$PROJECT_ROOT/services/catalog" && docker build -t catalog-service:latest .) || { log_error "Catalog image build failed"; exit 1; }
            minikube image load catalog-service:latest
            log_info "Catalog image built and loaded successfully"
            ;;
            inventory)
            log_info "Building inventory-service image..."
            (cd "$PROJECT_ROOT" && docker build -t inventory-service:latest -f services/inventory/Dockerfile services/inventory) || { log_error "Inventory image build failed"; exit 1; }
            minikube image load inventory-service:latest
            log_info "Inventory image built and loaded successfully"
            ;;
            cart)
                log_info "Building cart-service image..."
                (cd "$PROJECT_ROOT/services/cart" && docker build -t cart-service:latest .) || { log_error "Cart image build failed"; exit 1; }
                minikube image load cart-service:latest
                log_info "Cart image built and loaded successfully"
                ;;
            order)
                log_info "Building order-service image..."
                (cd "$PROJECT_ROOT/services/order" && docker build -t order-service:latest .) || { log_error "Order image build failed"; exit 1; }
                minikube image load order-service:latest
                log_info "Order image built and loaded successfully"
                ;;
        *)
            log_error "Unknown service: $service_name"
            log_info "Available services: ledger, payments, frontend, admin-console, seller-console, user, catalog, inventory, cart, order"
            exit 1
            ;;
    esac
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
    kubectl apply -f "$K8S_DIR/ledger/ingress.yaml" &
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
    kubectl apply -f "$K8S_DIR/ui/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/frontend/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/frontend/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/frontend/service.yaml" &
    kubectl apply -f "$K8S_DIR/frontend/ingress.yaml" &
    wait
}

deploy_admin_console() {
    log_info "Deploying Admin Console..."
    kubectl apply -f "$K8S_DIR/ui/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/admin-console/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/admin-console/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/admin-console/service.yaml" &
    kubectl apply -f "$K8S_DIR/admin-console/ingress.yaml" &
    wait
}

deploy_seller_console() {
    log_info "Deploying Seller Console..."
    kubectl apply -f "$K8S_DIR/ui/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/seller-console/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/seller-console/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/seller-console/service.yaml" &
    kubectl apply -f "$K8S_DIR/seller-console/ingress.yaml" &
    wait
}

deploy_user() {
    log_info "Deploying User Service..."
    kubectl apply -f "$K8S_DIR/user/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/user/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/user/secret.yaml" &
    kubectl apply -f "$K8S_DIR/user/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/user/service.yaml" &
    kubectl apply -f "$K8S_DIR/user/ingress.yaml" &
    wait
}

deploy_catalog() {
    log_info "Deploying Catalog Service..."
    kubectl apply -f "$K8S_DIR/catalog/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/catalog/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/catalog/secret.yaml" &
    kubectl apply -f "$K8S_DIR/catalog/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/catalog/service.yaml" &
    kubectl apply -f "$K8S_DIR/catalog/ingress.yaml" &
    wait
}

deploy_inventory() {
    log_info "Deploying Inventory Service..."
    kubectl apply -f "$K8S_DIR/inventory/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/inventory/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/inventory/secret.yaml" &
    kubectl apply -f "$K8S_DIR/inventory/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/inventory/service.yaml" &
    kubectl apply -f "$K8S_DIR/inventory/ingress.yaml" &
    wait
}

deploy_cart() {
    log_info "Deploying Cart Service..."
    kubectl apply -f "$K8S_DIR/cart/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/cart/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/cart/secret.yaml" &
    kubectl apply -f "$K8S_DIR/cart/service.yaml" &
    kubectl apply -f "$K8S_DIR/cart/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/cart/ingress.yaml" &
    wait
}

deploy_order() {
    log_info "Deploying Order Service..."
    kubectl apply -f "$K8S_DIR/order/namespace.yaml" &
    kubectl apply -f "$K8S_DIR/order/configmap.yaml" &
    kubectl apply -f "$K8S_DIR/order/secret.yaml" &
    kubectl apply -f "$K8S_DIR/order/service.yaml" &
    kubectl apply -f "$K8S_DIR/order/deployment.yaml" &
    kubectl apply -f "$K8S_DIR/order/ingress.yaml" &
    wait
}

wait_for_services() {
    local service_name="${1:-all}"
    
    if [[ "$service_name" != "all" ]]; then
        wait_for_single_service "$service_name"
        return
    fi
    
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
    kubectl wait --for=condition=available deployment/frontend -n "ui" --timeout=300s || log_warn "Frontend not ready yet" &
    FRONTEND_PID=$!
    
    log_info "Waiting for Admin Console..."
    kubectl wait --for=condition=available deployment/admin-console -n "ui" --timeout=300s || log_warn "Admin Console not ready yet" &
    ADMIN_CONSOLE_PID=$!
    
    log_info "Waiting for Seller Console..."
    kubectl wait --for=condition=available deployment/seller-console -n "ui" --timeout=300s || log_warn "Seller Console not ready yet" &
    SELLER_CONSOLE_PID=$!
    
    log_info "Waiting for User Service..."
    kubectl wait --for=condition=available deployment/user-service -n user --timeout=300s || log_warn "User Service not ready yet" &
    USER_PID=$!
    
    log_info "Waiting for Catalog Service..."
    kubectl wait --for=condition=available deployment/catalog-service -n "catalog" --timeout=300s || log_warn "Catalog Service not ready yet" &
    CATALOG_PID=$!
    
    log_info "Waiting for Inventory Service..."
    kubectl wait --for=condition=available deployment/inventory-service -n "inventory" --timeout=300s || log_warn "Inventory Service not ready yet" &
    INVENTORY_PID=$!
    
    log_info "Waiting for Cart Service..."
    kubectl wait --for=condition=available deployment/cart-service -n cart --timeout=300s || log_warn "Cart Service not ready yet" &
    CART_PID=$!
    
    log_info "Waiting for Order Service..."
    kubectl wait --for=condition=available deployment/order-service -n order --timeout=300s || log_warn "Order Service not ready yet" &
    ORDER_PID=$!
    
    # Wait for all services to be ready
    wait $POSTGRES_PID
    wait $LEDGER_PID
    wait $PAYMENTS_PID
    wait $FRONTEND_PID
    wait $ADMIN_CONSOLE_PID
    wait $SELLER_CONSOLE_PID
    wait $USER_PID
    wait $CATALOG_PID
    wait $INVENTORY_PID
    wait $CART_PID
    wait $ORDER_PID
    
    log_info "Services deployment complete"
}

wait_for_single_service() {
    local service_name="$1"
    
    case "$service_name" in
        ledger)
            log_info "Waiting for Ledger Service..."
            kubectl wait --for=condition=available deployment/ledger-service -n "$NAMESPACE" --timeout=300s || log_warn "Ledger Service not ready yet"
            ;;
        payments)
            log_info "Waiting for Payments Service..."
            kubectl wait --for=condition=available deployment/payments-service -n "$NAMESPACE" --timeout=300s || log_warn "Payments Service not ready yet"
            ;;
        frontend)
            log_info "Waiting for Frontend..."
            kubectl wait --for=condition=available deployment/frontend -n "ui" --timeout=300s || log_warn "Frontend not ready yet"
            ;;
        admin-console)
            log_info "Waiting for Admin Console..."
            kubectl wait --for=condition=available deployment/admin-console -n "ui" --timeout=300s || log_warn "Admin Console not ready yet"
            ;;
        seller-console)
            log_info "Waiting for Seller Console..."
            kubectl wait --for=condition=available deployment/seller-console -n "ui" --timeout=300s || log_warn "Seller Console not ready yet"
            ;;
            user)
            log_info "Waiting for User Service..."
            kubectl wait --for=condition=available deployment/user-service -n user --timeout=300s || log_warn "User Service not ready yet"
            ;;
            catalog)
            log_info "Waiting for Catalog Service..."
            kubectl wait --for=condition=available deployment/catalog-service -n "catalog" --timeout=300s || log_warn "Catalog Service not ready yet"
            ;;
            inventory)
            log_info "Waiting for Inventory Service..."
            kubectl wait --for=condition=available deployment/inventory-service -n "inventory" --timeout=300s || log_warn "Inventory Service not ready yet"
            ;;
            cart)
                log_info "Waiting for Cart Service..."
                kubectl wait --for=condition=available deployment/cart-service -n cart --timeout=300s || log_warn "Cart Service not ready yet"
                ;;
            order)
                log_info "Waiting for Order Service..."
                kubectl wait --for=condition=available deployment/order-service -n order --timeout=300s || log_warn "Order Service not ready yet"
                ;;
        esac
}

show_status() {
    log_info "Deployment Status:"
    echo ""
    echo "=== UI Namespace ==="
    kubectl get pods -n "ui"
    echo ""
    kubectl get services -n "ui"
    echo ""
    kubectl get ingress -n "ui"
    echo ""
    echo "=== Catalog Namespace ==="
    kubectl get pods -n "catalog"
    echo ""
    kubectl get services -n "catalog"
    echo ""
    kubectl get ingress -n "catalog"
    echo ""
    echo "=== Inventory Namespace ==="
    kubectl get pods -n "inventory"
    echo ""
    kubectl get services -n "inventory"
    echo ""
    kubectl get ingress -n "inventory"
    echo ""
    echo "=== Payments Platform Namespace ==="
    kubectl get pods -n "$NAMESPACE"
    echo ""
    kubectl get services -n "$NAMESPACE"
    echo ""
    kubectl get ingress -n "$NAMESPACE"
    echo ""
    echo "=== Order Namespace ==="
    kubectl get pods -n "order"
    echo ""
    kubectl get services -n "order"
    echo ""
    kubectl get ingress -n "order"
    echo ""
    
    log_info "Access URLs:"
    MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "localhost")
    INGRESS_IP=$(kubectl get ingress frontend-ingress -n "ui" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    
    if [ -n "$INGRESS_IP" ]; then
        echo "  Frontend (Ingress): http://buyit.local"
    else
        echo "  Frontend (Ingress): http://buyit.local"
        echo "    Add to /etc/hosts: $MINIKUBE_IP buyit.local"
        echo "    Or use port-forward: kubectl port-forward -n ui svc/frontend 3000:80"
    fi
    
    INGRESS_IP_ADMIN=$(kubectl get ingress admin-console-ingress -n "ui" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    if [ -n "$INGRESS_IP_ADMIN" ]; then
        echo "  Admin Console (Ingress): http://admin.local"
    else
        echo "  Admin Console (Ingress): http://admin.local"
        echo "    Add to /etc/hosts: $MINIKUBE_IP admin.local"
        echo "    Or use port-forward: kubectl port-forward -n ui svc/admin-console 3001:80"
    fi
    
    INGRESS_IP_SELLER=$(kubectl get ingress seller-console-ingress -n "ui" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    if [ -n "$INGRESS_IP_SELLER" ]; then
        echo "  Seller Console (Ingress): http://seller.local"
    else
        echo "  Seller Console (Ingress): http://seller.local"
        echo "    Add to /etc/hosts: $MINIKUBE_IP seller.local"
        echo "    Or use port-forward: kubectl port-forward -n ui svc/seller-console 3002:80"
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
    echo "  Frontend: kubectl port-forward -n ui svc/frontend 3000:80"
    echo "  Admin Console: kubectl port-forward -n ui svc/admin-console 3001:80"
    echo "  Seller Console: kubectl port-forward -n ui svc/seller-console 3002:80"
    echo "  Payments: kubectl port-forward -n $NAMESPACE svc/payments-service 8080:8080"
    echo "  Ledger: kubectl port-forward -n $NAMESPACE svc/ledger-service 8081:8081"
}

# Main execution
main() {
    log_info "Starting deployment to minikube..."
    
    check_prerequisites
    build_images "all"
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
    deploy_seller_console &
    deploy_user &
    deploy_catalog &
    deploy_inventory &
    deploy_cart &
    deploy_order &
    
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
VALID_SERVICES=("ledger" "payments" "frontend" "admin-console" "seller-console" "user" "catalog" "inventory" "cart" "order")
SERVICES_TO_DEPLOY=()
EXCLUDED_SERVICES=()
EXCLUDE_MODE=false

# Parse exclude flags and other arguments
ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        -x|--exclude)
            EXCLUDE_MODE=true
            shift
            if [[ $# -eq 0 ]]; then
                log_error "-x/--exclude requires a service name"
                exit 1
            fi
            # Validate the service name
            is_valid=false
            for valid_service in "${VALID_SERVICES[@]}"; do
                if [[ "$1" == "$valid_service" ]]; then
                    is_valid=true
                    EXCLUDED_SERVICES+=("$1")
                    break
                fi
            done
            if [[ "$is_valid" == false ]]; then
                log_error "Unknown service to exclude: $1"
                log_error "Available services: ${VALID_SERVICES[*]}"
                exit 1
            fi
            shift
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

# If exclude mode is active, calculate services to deploy/build
if [[ "$EXCLUDE_MODE" == true ]]; then
    if [[ ${#EXCLUDED_SERVICES[@]} -eq 0 ]]; then
        log_error "No services specified for exclusion"
        exit 1
    fi
    
    # Start with all services and remove excluded ones
    for service in "${VALID_SERVICES[@]}"; do
        should_exclude=false
        for excluded in "${EXCLUDED_SERVICES[@]}"; do
            if [[ "$service" == "$excluded" ]]; then
                should_exclude=true
                break
            fi
        done
        if [[ "$should_exclude" == false ]]; then
            SERVICES_TO_DEPLOY+=("$service")
        fi
    done
    
    if [[ ${#SERVICES_TO_DEPLOY[@]} -eq 0 ]]; then
        log_error "Cannot exclude all services. At least one service must be deployed/built."
        exit 1
    fi
fi

# Check if first argument is a command (not a service)
if [[ ${#ARGS[@]} -eq 0 ]]; then
    # No arguments - deploy all services (or all except excluded)
    if [[ "$EXCLUDE_MODE" == true ]]; then
        log_info "Excluding services: ${EXCLUDED_SERVICES[*]}"
        log_info "Deploying services: ${SERVICES_TO_DEPLOY[*]} (in parallel)..."
        check_prerequisites
        
        # Delete existing deployments first (before building images)
        delete_deployments "${SERVICES_TO_DEPLOY[@]}"
        
        # Build images for the specified services
        build_images "${SERVICES_TO_DEPLOY[@]}"
        
        # Deploy the services
        deploy_multiple_services "${SERVICES_TO_DEPLOY[@]}"
        
        log_info "Services deployed successfully: ${SERVICES_TO_DEPLOY[*]}"
        show_status
    else
        main
    fi
    exit 0
fi

FIRST_ARG="${ARGS[0]}"

case "$FIRST_ARG" in
    build)
        check_prerequisites
        if [[ "$EXCLUDE_MODE" == true ]]; then
            # Build all except excluded services
            log_info "Excluding services: ${EXCLUDED_SERVICES[*]}"
            log_info "Building services: ${SERVICES_TO_DEPLOY[*]} (in parallel)..."
            build_images "${SERVICES_TO_DEPLOY[@]}"
        elif [[ ${#ARGS[@]} -eq 1 ]]; then
            build_images "all"
        else
            # Build specific services
            build_images "${ARGS[@]:1}"
        fi
        exit 0
        ;;
    ingress)
        check_prerequisites
        deploy_ingress
        exit 0
        ;;
    postgres)
        log_warn "PostgreSQL is now deployed via Terraform. Use 'terraform apply' in infra/minikube/"
        exit 0
        ;;
    status)
        show_status
        exit 0
        ;;
    *)
        # If exclude mode is active, we've already calculated SERVICES_TO_DEPLOY
        if [[ "$EXCLUDE_MODE" == true ]]; then
            log_info "Excluding services: ${EXCLUDED_SERVICES[*]}"
            log_info "Deploying services: ${SERVICES_TO_DEPLOY[*]} (in parallel)..."
            check_prerequisites
            
            # Delete existing deployments first (before building images)
            delete_deployments "${SERVICES_TO_DEPLOY[@]}"
            
            # Build images for the specified services
            build_images "${SERVICES_TO_DEPLOY[@]}"
            
            # Deploy the services
            deploy_multiple_services "${SERVICES_TO_DEPLOY[@]}"
            
            log_info "Services deployed successfully: ${SERVICES_TO_DEPLOY[*]}"
            show_status
            exit 0
        fi
        
        # Parse service names
        for arg in "${ARGS[@]}"; do
            # Check if it's a valid service
            is_valid=false
            for valid_service in "${VALID_SERVICES[@]}"; do
                if [[ "$arg" == "$valid_service" ]]; then
                    is_valid=true
                    SERVICES_TO_DEPLOY+=("$arg")
                    break
                fi
            done
            
            if [[ "$is_valid" == false ]]; then
                log_error "Unknown service or command: $arg"
                echo ""
                echo "Usage:"
                echo "  ./deploy.sh                                          - Deploy all services from scratch"
                echo "  ./deploy.sh <service1> [<service2> ...]               - Deploy one or more services (in parallel)"
                echo "  ./deploy.sh -x <service1> [-x <service2> ...]        - Deploy all services except excluded ones"
                echo "  ./deploy.sh build -x <service1> [-x <service2> ...]  - Build all services except excluded ones"
                echo ""
                echo "Available services:"
                echo "  ${VALID_SERVICES[*]}"
                echo ""
                echo "Other commands:"
                echo "  ./deploy.sh build [<service1> [<service2> ...]]       - Build Docker images (all or specific services)"
                echo "  ./deploy.sh ingress                                   - Deploy ingress controller"
                echo "  ./deploy.sh status                                    - Show deployment status"
                echo ""
                echo "Examples:"
                echo "  ./deploy.sh payments ledger                           - Deploy payments and ledger services in parallel"
                echo "  ./deploy.sh frontend admin-console seller-console     - Deploy all frontend services in parallel"
                echo "  ./deploy.sh -x ledger -x payments                     - Deploy all services except ledger and payments"
                echo "  ./deploy.sh build -x ledger -x payments               - Build all services except ledger and payments"
                exit 1
            fi
        done
        
        # Deploy the specified services
        if [[ ${#SERVICES_TO_DEPLOY[@]} -eq 0 ]]; then
            log_error "No services specified"
            exit 1
        fi
        
        log_info "Deploying services: ${SERVICES_TO_DEPLOY[*]} (in parallel)..."
        check_prerequisites
        
        # Delete existing deployments first (before building images)
        delete_deployments "${SERVICES_TO_DEPLOY[@]}"
        
        # Build images for the specified services
        build_images "${SERVICES_TO_DEPLOY[@]}"
        
        # Deploy the services
        deploy_multiple_services "${SERVICES_TO_DEPLOY[@]}"
        
        log_info "Services deployed successfully: ${SERVICES_TO_DEPLOY[*]}"
        show_status
        ;;
esac

