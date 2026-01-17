output "namespace" {
  description = "Kubernetes namespace where Prometheus is deployed"
  value       = kubernetes_namespace.prometheus.metadata[0].name
}

output "prometheus_service_name" {
  description = "Prometheus service name"
  value       = "kube-prometheus-stack-prometheus"
}

output "prometheus_service_url" {
  description = "Prometheus service URL"
  value       = "http://kube-prometheus-stack-prometheus.${kubernetes_namespace.prometheus.metadata[0].name}.svc.cluster.local:9090"
}

output "grafana_service_name" {
  description = "Grafana service name"
  value       = "kube-prometheus-stack-grafana"
}

output "grafana_service_url" {
  description = "Grafana service URL (NodePort)"
  value       = "http://$(minikube ip):30000"
}

output "prometheus_ready" {
  description = "Indicates that Prometheus is ready"
  value       = null_resource.wait_for_prometheus.id
  depends_on  = [null_resource.wait_for_prometheus]
}
