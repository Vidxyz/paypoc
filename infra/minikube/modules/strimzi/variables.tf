variable "namespace" {
  description = "Kubernetes namespace for Strimzi Kafka"
  type        = string
}

variable "chart_version" {
  description = "Version of the Strimzi Kafka Operator Helm chart"
  type        = string
  default     = "0.45.0"
}

