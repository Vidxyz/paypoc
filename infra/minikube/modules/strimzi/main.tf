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

# Add Strimzi Helm repository
resource "helm_release" "strimzi_kafka_operator" {
  name       = "strimzi-kafka-operator"
  repository = "https://strimzi.io/charts/"
  chart      = "strimzi-kafka-operator"
  version    = var.chart_version
  namespace  = var.namespace
  wait       = true
  timeout    = 600
  wait_for_jobs = true

  set {
    name  = "watchAnyNamespace"
    value = "true"
  }

  
}

# Wait for CRDs to be installed by the operator
# The Strimzi operator installs CRDs, but they need time to be registered in the API server
# resource "time_sleep" "wait_for_crds" {
#   depends_on = [helm_release.strimzi_kafka_operator]
#   create_duration = "30s"
# }

# Create Kafka cluster
resource "kubernetes_manifest" "kafka_cluster" {
  depends_on = [helm_release.strimzi_kafka_operator]

  manifest = {
    apiVersion = "kafka.strimzi.io/v1beta2"
    kind       = "Kafka"
    metadata = {
      name      = "kafka-cluster"
      namespace = var.namespace
    }
    spec = {
      kafka = {
        replicas = 1
        version  = "3.9.0"
        listeners = [
          {
            name = "plain"
            port = 9092
            type = "internal"
            tls  = false
          }
        ]
        config = {
          "offsets.topic.replication.factor"             = "1"
          "transaction.state.log.replication.factor"    = "1"
          "transaction.state.log.min.isr"               = "1"
          "default.replication.factor"                  = "1"
          "min.insync.replicas"                         = "1"
          "inter.broker.protocol.version"               = "3.6"
          "log.message.format.version"                  = "3.6"
        }
        storage = {
          type = "ephemeral"
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
      zookeeper = {
        replicas = 1
        storage = {
          type = "ephemeral"
        }
        resources = {
          requests = {
            memory = "128Mi"
            cpu    = "100m"
          }
          limits = {
            memory = "256Mi"
            cpu    = "300m"
          }
        }
      }
      entityOperator = {
        topicOperator = {
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
        userOperator = {
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
      }
    }
  }
}

# Wait for Kafka cluster to be ready before other resources try to connect
# resource "time_sleep" "wait_for_kafka" {
#   depends_on = [kubernetes_manifest.kafka_cluster]
#   create_duration = "180s"  # Allow Kafka cluster to fully start (3 minutes)
# }

