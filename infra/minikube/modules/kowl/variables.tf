variable "namespace" {
  description = "Kubernetes namespace for KOWL"
  type        = string
}

variable "kafka_bootstrap_servers" {
  description = "Kafka bootstrap servers address"
  type        = string
}

variable "chart_version" {
  description = "Version of the Redpanda Console Helm chart"
  type        = string
  default     = "3.3.0"
}

