terraform {
  required_version = ">= 1.0"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.23"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.11"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.9"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"
    }
  }
}

# Configure Kubernetes provider for minikube
provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kube_context
}

# Configure Helm provider
provider "helm" {
  kubernetes {
    config_path    = var.kubeconfig_path
    config_context  = var.kube_context
  }
}

# Create namespace for Kafka
resource "kubernetes_namespace" "kafka" {
  metadata {
    name = var.kafka_namespace
    labels = {
      app = "kafka"
    }
  }
}

# Module for Strimzi Kafka Operator
module "strimzi" {
  source = "./modules/strimzi"
  
  namespace = kubernetes_namespace.kafka.metadata[0].name
  chart_version = var.strimzi_chart_version
}

# Module for KOWL UI
# Wait for Kafka to be fully ready before starting KOWL
module "kowl" {
  source = "./modules/kowl"
  
  namespace = kubernetes_namespace.kafka.metadata[0].name
  kafka_bootstrap_servers = module.strimzi.bootstrap_servers
  chart_version = var.redpanda_console_chart_version
  
  depends_on = [module.strimzi.kafka_ready]
}

# Create namespace for payments platform
resource "kubernetes_namespace" "payments_platform" {
  metadata {
    name = var.payments_namespace
    labels = {
      app = "payments-platform"
    }
  }
}

# Module for PostgreSQL
module "postgres" {
  source = "./modules/postgres"
  
  namespace = kubernetes_namespace.payments_platform.metadata[0].name
  chart_version = var.postgres_chart_version
  postgres_username = var.postgres_username
  postgres_password = var.postgres_password
}

