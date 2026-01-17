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

# Create namespace for Prometheus
resource "kubernetes_namespace" "prometheus" {
  metadata {
    name = var.namespace
    labels = {
      app = "prometheus"
    }
  }
}

# Deploy kube-prometheus-stack using Helm
# This includes Prometheus, Grafana, node-exporter, and kube-state-metrics
resource "helm_release" "prometheus" {
  name       = "kube-prometheus-stack"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
  version    = var.chart_version
  namespace  = kubernetes_namespace.prometheus.metadata[0].name
  wait       = true
  timeout    = 600

  values = [
    yamlencode({
      # Prometheus configuration
      prometheus = {
        prometheusSpec = {
          retention = "7d"
          resources = {
            requests = {
              memory = "512Mi"
              cpu    = "250m"
            }
            limits = {
              memory = "1Gi"
              cpu    = "500m"
            }
          }
          # Enable service monitor discovery
          serviceMonitorSelectorNilUsesHelmValues = false
          podMonitorSelectorNilUsesHelmValues     = false
          ruleSelectorNilUsesHelmValues            = false
        }
      }

      # Grafana configuration
      grafana = {
        enabled = true
        adminUser = "admin"
        adminPassword = var.grafana_admin_password
        service = {
          type = "NodePort"
          nodePort = 30000
        }
        resources = {
          requests = {
            memory = "128Mi"
            cpu    = "100m"
          }
          limits = {
            memory = "256Mi"
            cpu    = "200m"
          }
        }
      }

      # Node exporter (for node metrics)
      nodeExporter = {
        enabled = true
        resources = {
          requests = {
            memory = "64Mi"
            cpu    = "50m"
          }
          limits = {
            memory = "128Mi"
            cpu    = "100m"
          }
        }
      }

      # kube-state-metrics (for K8s object metrics)
      kubeStateMetrics = {
        enabled = true
        resources = {
          requests = {
            memory = "64Mi"
            cpu    = "50m"
          }
          limits = {
            memory = "128Mi"
            cpu    = "100m"
          }
        }
      }

      # Alertmanager (optional, disabled for minikube)
      alertmanager = {
        enabled = false
      }

      # Disable some components to reduce resource usage in minikube
      prometheusOperator = {
        resources = {
          requests = {
            memory = "128Mi"
            cpu    = "100m"
          }
          limits = {
            memory = "256Mi"
            cpu    = "200m"
          }
        }
      }
    })
  ]
}

# Wait for Prometheus to be ready
resource "null_resource" "wait_for_prometheus" {
  depends_on = [helm_release.prometheus]

  provisioner "local-exec" {
    command = <<-EOT
      set -e
      echo "Waiting for Prometheus to be ready..."
      kubectl wait --for=condition=ready pod \
        -l app.kubernetes.io/name=prometheus,app.kubernetes.io/instance=kube-prometheus-stack \
        -n ${var.namespace} \
        --timeout=300s || true
      
      echo "Waiting for Grafana to be ready..."
      kubectl wait --for=condition=ready pod \
        -l app.kubernetes.io/name=grafana,app.kubernetes.io/instance=kube-prometheus-stack \
        -n ${var.namespace} \
        --timeout=300s || true
      
      echo "Prometheus stack is ready"
    EOT
  }

  triggers = {
    prometheus_release = helm_release.prometheus.id
  }
}
