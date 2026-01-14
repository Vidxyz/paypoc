variable "kubeconfig_path" {
  description = "Path to kubeconfig file (defaults to ~/.kube/config)"
  type        = string
  default     = "~/.kube/config"
}

variable "kube_context" {
  description = "Kubernetes context to use (defaults to minikube)"
  type        = string
  default     = "minikube"
}

variable "kafka_namespace" {
  description = "Kubernetes namespace for Kafka resources"
  type        = string
  default     = "kafka"
}

variable "strimzi_chart_version" {
  description = "Version of the Strimzi Kafka Operator Helm chart"
  type        = string
  default     = "0.45.0"
}

variable "redpanda_console_chart_version" {
  description = "Version of the RedPanda Console Helm chart"
  type        = string
  default     = "3.3.0"
}

variable "payments_namespace" {
  description = "Kubernetes namespace for payments platform services"
  type        = string
  default     = "payments-platform"
}

variable "postgres_chart_version" {
  description = "Version of the Bitnami PostgreSQL Helm chart"
  type        = string
  default     = "15.5.x"
}

variable "postgres_username" {
  description = "PostgreSQL admin username"
  type        = string
  default     = "postgres"
}

variable "postgres_password" {
  description = "PostgreSQL admin password"
  type        = string
  default     = "postgres"
  sensitive   = true
}

variable "postgres_namespace" {
  description = "Kubernetes namespace for PostgreSQL"
  type        = string
  default     = "postgres"
}

variable "redis_namespace" {
  description = "Kubernetes namespace for Redis"
  type        = string
  default     = "redis"
}

variable "redis_chart_version" {
  description = "Version of the Bitnami Redis Helm chart"
  type        = string
  default     = "19.1.0"
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


