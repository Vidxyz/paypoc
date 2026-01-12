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


