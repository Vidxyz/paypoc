variable "namespace" {
  description = "Kubernetes namespace for Redis"
  type        = string
}

variable "chart_version" {
  description = "Version of the Bitnami Redis Helm chart (leave empty for latest)"
  type        = string
  default     = ""  # Use latest available chart version
}

variable "redis_password" {
  description = "Redis password (leave empty for no password)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "redis_replica_count" {
  description = "Number of Redis replicas"
  type        = number
  default     = 1
}

variable "redis_memory_limit" {
  description = "Memory limit for Redis (e.g., '256Mi')"
  type        = string
  default     = "256Mi"
}

variable "redis_cpu_limit" {
  description = "CPU limit for Redis (e.g., '500m')"
  type        = string
  default     = "500m"
}

variable "redis_image_tag" {
  description = "Redis image tag to use (default: 7.2 - Debian-based with bash)"
  type        = string
  default     = "7.2"
}

