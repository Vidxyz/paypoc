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

output "payments_namespace" {
  description = "Kubernetes namespace where payments platform services are deployed"
  value       = kubernetes_namespace.payments_platform.metadata[0].name
}

output "postgres_service_host" {
  description = "PostgreSQL service host"
  value       = module.postgres.service_host
}

output "postgres_service_port" {
  description = "PostgreSQL service port"
  value       = module.postgres.service_port
}

output "postgres_databases_initialized" {
  description = "Confirmation that PostgreSQL databases and users have been initialized"
  value       = module.postgres.databases_initialized
}

output "postgres_namespace" {
  description = "Kubernetes namespace where PostgreSQL is deployed"
  value       = kubernetes_namespace.postgres.metadata[0].name
}

output "redis_service_host" {
  description = "Redis service host"
  value       = module.redis.service_host
}

output "redis_service_port" {
  description = "Redis service port"
  value       = module.redis.service_port
}

output "redis_namespace" {
  description = "Kubernetes namespace where Redis is deployed"
  value       = kubernetes_namespace.redis.metadata[0].name
}


