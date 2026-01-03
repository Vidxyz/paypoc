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

resource "helm_release" "kowl" {
  name       = "console"
  repository = "https://charts.redpanda.com"
  chart      = "console"
  version    = var.chart_version
  namespace  = var.namespace
  wait       = false
  timeout    = 300

  values = [
    yamlencode({
      config = {
        kafka = {
          brokers = [var.kafka_bootstrap_servers]
        }
        server = {
          listenAddress = "0.0.0.0"
          listenPort    = 8080
        }
      }
      service = {
        type     = "NodePort"
        nodePort = 30080
      }
    })
  ]
}


# Note: KOWL UI is exposed on NodePort 30080
# Access it via: http://$(minikube ip):30080

