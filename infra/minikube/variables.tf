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

variable "kowl_chart_version" {
  description = "Version of the Redpanda Console Helm chart"
  type        = string
  default     = "3.3.0"
}

