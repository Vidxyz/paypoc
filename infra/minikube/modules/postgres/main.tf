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
  }
}

# Deploy PostgreSQL using Bitnami Helm chart
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
      
      # Create additional databases
      initdbScripts = {
        "01-init-databases.sh" = <<-EOT
          #!/bin/bash
          set -e
          psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
              CREATE DATABASE ledger_db;
              CREATE DATABASE payments_db;
              
              -- Create ledger user
              CREATE USER ledger_user WITH PASSWORD 'ledger_password';
              GRANT ALL PRIVILEGES ON DATABASE ledger_db TO ledger_user;
              
              -- Grant permissions on public schema
              \c ledger_db
              GRANT ALL ON SCHEMA public TO ledger_user;
          EOSQL
        EOT
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

# The Bitnami PostgreSQL Helm chart creates a service automatically named "postgres-postgresql"
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
      "app.kubernetes.io/name"     = "postgresql"
      "app.kubernetes.io/instance" = "postgres"
    }
  }
  
  depends_on = [helm_release.postgres]
}

