output "kafka_namespace" {
  description = "Kubernetes namespace where Kafka is deployed"
  value       = kubernetes_namespace.kafka.metadata[0].name
}

output "kafka_bootstrap_servers" {
  description = "Kafka bootstrap servers address"
  value       = module.strimzi.bootstrap_servers
}

output "kowl_ui_url" {
  description = "URL to access KOWL UI"
  value       = module.kowl.ui_url
}

