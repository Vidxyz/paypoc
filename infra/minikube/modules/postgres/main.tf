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

# Wait for PostgreSQL pod to be ready and accepting connections
resource "null_resource" "wait_for_postgres" {
  depends_on = [helm_release.postgres, kubernetes_service.postgres_alias]
  
  provisioner "local-exec" {
    command = <<-EOT
      set -e
      echo "Waiting for PostgreSQL pod to be ready..."
      kubectl wait --for=condition=ready pod \
        -l app.kubernetes.io/name=postgres,app.kubernetes.io/instance=postgres \
        -n ${var.namespace} \
        --timeout=300s
      
      # Get pod name and admin user
      POD_NAME=$(kubectl get pods -n ${var.namespace} -l app.kubernetes.io/name=postgres,app.kubernetes.io/instance=postgres -o jsonpath='{.items[0].metadata.name}')
      ADMIN_USER=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- printenv POSTGRES_USER 2>/dev/null || echo "${var.postgres_username}")
      if [ -z "$ADMIN_USER" ]; then
        ADMIN_USER="${var.postgres_username}"
      fi
      
      # Wait for PostgreSQL to accept connections (retry up to 30 times)
      echo "Waiting for PostgreSQL to accept connections..."
      for i in {1..30}; do
        if kubectl exec -n ${var.namespace} "$POD_NAME" -- psql -U "$ADMIN_USER" -d postgres -c "SELECT 1;" >/dev/null 2>&1; then
          echo "PostgreSQL is ready and accepting connections"
          break
        fi
        if [ $i -eq 30 ]; then
          echo "Error: PostgreSQL did not become ready after 30 attempts"
          exit 1
        fi
        echo "Attempt $i/30: PostgreSQL not ready yet, waiting..."
        sleep 2
      done
    EOT
  }
  
  triggers = {
    postgres_release = helm_release.postgres.id
    postgres_service = kubernetes_service.postgres_alias.id
  }
}

# Initialize databases and user using kubectl exec
resource "null_resource" "init_databases" {
  depends_on = [null_resource.wait_for_postgres]
  
  provisioner "local-exec" {
    command = <<-EOT
      set -e
      echo "=== Starting database initialization ==="
      
      # Get the PostgreSQL pod name
      POD_NAME=$(kubectl get pods -n ${var.namespace} -l app.kubernetes.io/name=postgres,app.kubernetes.io/instance=postgres -o jsonpath='{.items[0].metadata.name}')
      
      if [ -z "$POD_NAME" ]; then
        echo "Error: PostgreSQL pod not found"
        exit 1
      fi
      
      echo "PostgreSQL pod: $POD_NAME"
      
      # Get the actual admin username and password from the pod
      ADMIN_USER=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- printenv POSTGRES_USER 2>/dev/null || echo "${var.postgres_username}")
      ADMIN_PASSWORD=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- printenv POSTGRES_PASSWORD 2>/dev/null || echo "${var.postgres_password}")
      
      if [ -z "$ADMIN_USER" ]; then
        ADMIN_USER="${var.postgres_username}"
      fi
      if [ -z "$ADMIN_PASSWORD" ]; then
        ADMIN_PASSWORD="${var.postgres_password}"
      fi
      
      echo "Using admin user: $ADMIN_USER"
      
      # Set PGPASSWORD for password authentication
      export PGPASSWORD="$ADMIN_PASSWORD"
      
      # Create databases (CREATE DATABASE cannot be run inside DO blocks, so we check and create separately)
      echo "Creating databases..."
      DB_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='ledger_db'" 2>/dev/null || echo "")
      if [ -z "$DB_EXISTS" ]; then
        echo "Creating ledger_db..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE DATABASE ledger_db;" || echo "ledger_db may already exist"
      else
        echo "ledger_db already exists"
      fi
      
      DB_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='payments_db'" 2>/dev/null || echo "")
      if [ -z "$DB_EXISTS" ]; then
        echo "Creating payments_db..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE DATABASE payments_db;" || echo "payments_db may already exist"
      else
        echo "payments_db already exists"
      fi
      
      DB_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='users_db'" 2>/dev/null || echo "")
      if [ -z "$DB_EXISTS" ]; then
        echo "Creating users_db..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE DATABASE users_db;" || echo "users_db may already exist"
      else
        echo "users_db already exists"
      fi
      
      DB_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='auth_db'" 2>/dev/null || echo "")
      if [ -z "$DB_EXISTS" ]; then
        echo "Creating auth_db..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE DATABASE auth_db;" || echo "auth_db may already exist"
      else
        echo "auth_db already exists"
      fi
      
      DB_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='catalog_db'" 2>/dev/null || echo "")
      if [ -z "$DB_EXISTS" ]; then
        echo "Creating catalog_db..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE DATABASE catalog_db;" || echo "catalog_db may already exist"
      else
        echo "catalog_db already exists"
      fi
      
      DB_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='inventory_db'" 2>/dev/null || echo "")
      if [ -z "$DB_EXISTS" ]; then
        echo "Creating inventory_db..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE DATABASE inventory_db;" || echo "inventory_db may already exist"
      else
        echo "inventory_db already exists"
      fi
      
      # Create ledger_user (idempotent) - check first, then create
      echo "Creating ledger_user..."
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='ledger_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "Creating ledger_user..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE USER ledger_user WITH PASSWORD 'ledger_password';" || {
          echo "ERROR: Failed to create ledger_user"
          exit 1
        }
        echo "ledger_user created successfully"
      else
        echo "ledger_user already exists"
      fi
      
      # Create payments_user (idempotent) - check first, then create
      echo "Creating payments_user..."
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='payments_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "Creating payments_user..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE USER payments_user WITH PASSWORD 'payments_password';" || {
          echo "ERROR: Failed to create payments_user"
          exit 1
        }
        echo "payments_user created successfully"
      else
        echo "payments_user already exists"
      fi
      
      # Create users_user (idempotent) - check first, then create
      echo "Creating users_user..."
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='users_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "Creating users_user..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE USER users_user WITH PASSWORD 'users_password';" || {
          echo "ERROR: Failed to create users_user"
          exit 1
        }
        echo "users_user created successfully"
      else
        echo "users_user already exists"
      fi
      
      # Create auth_user (idempotent) - check first, then create
      echo "Creating auth_user..."
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='auth_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "Creating auth_user..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE USER auth_user WITH PASSWORD 'auth_password';" || {
          echo "ERROR: Failed to create auth_user"
          exit 1
        }
        echo "auth_user created successfully"
      else
        echo "auth_user already exists"
      fi
      
      # Create catalog_user (idempotent) - check first, then create
      echo "Creating catalog_user..."
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='catalog_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "Creating catalog_user..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE USER catalog_user WITH PASSWORD 'catalog_password';" || {
          echo "ERROR: Failed to create catalog_user"
          exit 1
        }
        echo "catalog_user created successfully"
      else
        echo "catalog_user already exists"
      fi
      
      # Create inventory_user (idempotent) - check first, then create
      echo "Creating inventory_user..."
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='inventory_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "Creating inventory_user..."
        kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "CREATE USER inventory_user WITH PASSWORD 'inventory_password';" || {
          echo "ERROR: Failed to create inventory_user"
          exit 1
        }
        echo "inventory_user created successfully"
      else
        echo "inventory_user already exists"
      fi
      
      # Verify users were created
      echo "Verifying users exist..."
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='ledger_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "ERROR: ledger_user was not created successfully"
        exit 1
      fi
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='payments_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "ERROR: payments_user was not created successfully"
        exit 1
      fi
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='users_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "ERROR: users_user was not created successfully"
        exit 1
      fi
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='auth_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "ERROR: auth_user was not created successfully"
        exit 1
      fi
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='catalog_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "ERROR: catalog_user was not created successfully"
        exit 1
      fi
      USER_EXISTS=$(kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='inventory_user'" 2>/dev/null || echo "")
      if [ -z "$USER_EXISTS" ]; then
        echo "ERROR: inventory_user was not created successfully"
        exit 1
      fi
      
      # Grant database privileges (only after users are confirmed to exist)
      echo "Granting database privileges..."
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE ledger_db TO ledger_user;" || {
        echo "ERROR: Failed to grant privileges on ledger_db to ledger_user"
        exit 1
      }
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE payments_db TO payments_user;" || {
        echo "ERROR: Failed to grant privileges on payments_db to payments_user"
        exit 1
      }
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE users_db TO users_user;" || {
        echo "ERROR: Failed to grant privileges on users_db to users_user"
        exit 1
      }
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE auth_db TO auth_user;" || {
        echo "ERROR: Failed to grant privileges on auth_db to auth_user"
        exit 1
      }
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE catalog_db TO catalog_user;" || {
        echo "ERROR: Failed to grant privileges on catalog_db to catalog_user"
        exit 1
      }
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE inventory_db TO inventory_user;" || {
        echo "ERROR: Failed to grant privileges on inventory_db to inventory_user"
        exit 1
      }
      echo "Database privileges granted successfully"
      
      # Grant schema permissions (connect to ledger_db)
      echo "Granting schema permissions on ledger_db..."
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d ledger_db <<-EOSQL
        GRANT ALL ON SCHEMA public TO ledger_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO ledger_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO ledger_user;
      EOSQL
      
      # Grant schema permissions (connect to payments_db)
      echo "Granting schema permissions on payments_db..."
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d payments_db <<-EOSQL
        GRANT ALL ON SCHEMA public TO payments_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO payments_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO payments_user;
      EOSQL
      
      # Grant schema permissions (connect to users_db)
      echo "Granting schema permissions on users_db..."
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d users_db <<-EOSQL
        GRANT ALL ON SCHEMA public TO users_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO users_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO users_user;
      EOSQL
      
      # Grant schema permissions (connect to auth_db)
      echo "Granting schema permissions on auth_db..."
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d auth_db <<-EOSQL
        GRANT ALL ON SCHEMA public TO auth_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO auth_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO auth_user;
      EOSQL
      
      # Grant schema permissions (connect to catalog_db)
      echo "Granting schema permissions on catalog_db..."
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d catalog_db <<-EOSQL
        GRANT ALL ON SCHEMA public TO catalog_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO catalog_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO catalog_user;
      EOSQL
      
      # Grant schema permissions (connect to inventory_db)
      echo "Granting schema permissions on inventory_db..."
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d inventory_db <<-EOSQL
        GRANT ALL ON SCHEMA public TO inventory_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO inventory_user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO inventory_user;
      EOSQL
      
      # Verify the users were created
      echo "Verifying users exist..."
      kubectl exec -n ${var.namespace} "$POD_NAME" -- env PGPASSWORD="$ADMIN_PASSWORD" psql -U "$ADMIN_USER" -d postgres -c "\du" | grep -E "ledger_user|payments_user|users_user|auth_user|catalog_user|inventory_user" || {
        echo "ERROR: Failed to verify user creation"
        exit 1
      }
      
      echo "=== Database initialization completed successfully ==="
    EOT
  }
  
  triggers = {
    postgres_ready = null_resource.wait_for_postgres.id
    # Re-run if credentials change
    postgres_username = var.postgres_username
    postgres_password = var.postgres_password
  }
}

