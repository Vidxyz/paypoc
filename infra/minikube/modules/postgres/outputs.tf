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

output "databases_initialized" {
  description = "Indicates that databases and users have been initialized"
  value       = null_resource.init_databases.id
  depends_on  = [null_resource.init_databases]
}

