variable "namespace" {
  description = "Kubernetes namespace for PostgreSQL"
  type        = string
}

variable "chart_version" {
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

