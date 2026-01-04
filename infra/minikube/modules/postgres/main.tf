terraform {
  required_providers {
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.11"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.23"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"
    }
  }
}

# Deploy PostgreSQL using custom Helm chart
resource "helm_release" "postgres" {
  name       = "postgres"
  repository = "https://raw.githubusercontent.com/hansehe/postgres-helm/master/helm/charts/postgres"
  chart   = "postgres"
  # version = var.chart_version
  namespace  = var.namespace
  wait       = true
  timeout    = 600

  values = [
    yamlencode({
      auth = {
        postgresPassword = var.postgres_password
        username        = var.postgres_username
        database        = "postgres"
      }
      
      primary = {
        persistence = {
          enabled      = true
          size         = "256Mi"
          storageClass = "standard"
        }
        resources = {
          requests = {
            memory = "256Mi"
            cpu    = "200m"
          }
          limits = {
            memory = "512Mi"
            cpu    = "500m"
          }
        }
      }
      
      # Disable metrics and other optional components for minikube
      metrics = {
        enabled = false
      }
    })
  ]
}

# The PostgreSQL Helm chart creates a service automatically
# We'll create a simpler service name alias for easier reference
resource "kubernetes_service" "postgres_alias" {
  metadata {
    name      = "postgres-service"
    namespace = var.namespace
    labels = {
      app = "postgres"
    }
  }
  
  spec {
    type = "ClusterIP"
    port {
      port        = 5432
      target_port = 5432
      protocol    = "TCP"
      name        = "postgres"
    }
    
    selector = {
      "app.kubernetes.io/name"     = "postgres"
      "app.kubernetes.io/instance" = "postgres"
    }
  }
  
  depends_on = [helm_release.postgres]
}

# Wait for PostgreSQL pod to be ready
resource "null_resource" "wait_for_postgres" {
  depends_on = [helm_release.postgres]
  
  provisioner "local-exec" {
    command = <<-EOT
      echo "Waiting for PostgreSQL pod to be ready..."
      kubectl wait --for=condition=ready pod \
        -l app.kubernetes.io/name=postgres,app.kubernetes.io/instance=postgres \
        -n ${var.namespace} \
        --timeout=300s
      
      # Additional wait to ensure PostgreSQL is accepting connections
      sleep 5
    EOT
  }
  
  triggers = {
    postgres_release = helm_release.postgres.id
  }
}

# Initialize databases and user using kubectl exec
resource "null_resource" "init_databases" {
  depends_on = [null_resource.wait_for_postgres]
  
  provisioner "local-exec" {
    command = <<-EOT
      # Get the PostgreSQL pod name
      POD_NAME=$(kubectl get pods -n ${var.namespace} -l app.kubernetes.io/name=postgres,app.kubernetes.io/instance=postgres -o jsonpath='{.items[0].metadata.name}')
      
      if [ -z "$POD_NAME" ]; then
        echo "Error: PostgreSQL pod not found"
        exit 1
      fi
      
      echo "Initializing databases and user on pod: $POD_NAME"
      
      # Get the actual admin username from the pod (might be different from var)
      ADMIN_USER=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- printenv POSTGRES_USER 2>/dev/null || echo "${var.postgres_username}")
      if [ -z "$ADMIN_USER" ]; then
        ADMIN_USER="${var.postgres_username}"
      fi
      
      echo "Using admin user: $ADMIN_USER"
      
      # Run initialization SQL (idempotent - will fail gracefully if already exists)
      kubectl exec -n ${var.namespace} "$POD_NAME" -- psql -U "$ADMIN_USER" -d postgres <<-EOSQL || true
        -- Create databases (IF NOT EXISTS doesn't work in PostgreSQL, so we use DO block)
        DO \$\$
        BEGIN
          IF NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ledger_db') THEN
            CREATE DATABASE ledger_db;
          END IF;
          IF NOT EXISTS (SELECT FROM pg_database WHERE datname = 'payments_db') THEN
            CREATE DATABASE payments_db;
          END IF;
        END
        \$\$;
        
        -- Create user (idempotent)
        DO \$\$
        BEGIN
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ledger_user') THEN
            CREATE USER ledger_user WITH PASSWORD 'ledger_password';
          END IF;
        END
        \$\$;
        
        -- Grant privileges
        GRANT ALL PRIVILEGES ON DATABASE ledger_db TO ledger_user;
        GRANT ALL PRIVILEGES ON DATABASE payments_db TO ledger_user;
      EOSQL
      
      # Grant schema permissions (connect to ledger_db)
      kubectl exec -n ${var.namespace} "$POD_NAME" -- psql -U "$ADMIN_USER" -d ledger_db <<-EOSQL || true
        GRANT ALL ON SCHEMA public TO ledger_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO ledger_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO ledger_user;
      EOSQL
      
      echo "Database initialization completed"
    EOT
  }
  
  triggers = {
    postgres_ready = null_resource.wait_for_postgres.id
    # Re-run if credentials change
    postgres_username = var.postgres_username
    postgres_password = var.postgres_password
  }
}

