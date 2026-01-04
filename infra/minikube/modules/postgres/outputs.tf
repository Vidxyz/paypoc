output "service_name" {
  description = "PostgreSQL service name"
  value       = kubernetes_service.postgres_alias.metadata[0].name
}

output "service_host" {
  description = "PostgreSQL service host"
  value       = "${kubernetes_service.postgres_alias.metadata[0].name}.${var.namespace}.svc.cluster.local"
}

output "service_port" {
  description = "PostgreSQL service port"
  value       = 5432
}

