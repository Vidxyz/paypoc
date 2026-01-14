output "service_name" {
  description = "Redis service name"
  value       = kubernetes_service.redis_alias.metadata[0].name
}

output "service_host" {
  description = "Redis service host"
  value       = "${kubernetes_service.redis_alias.metadata[0].name}.${var.namespace}.svc.cluster.local"
}

output "service_port" {
  description = "Redis service port"
  value       = 6379
}

output "redis_ready" {
  description = "Indicates that Redis is ready and accepting connections"
  value       = null_resource.wait_for_redis.id
  depends_on  = [null_resource.wait_for_redis]
}

