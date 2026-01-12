output "cert_manager_namespace" {
  description = "Namespace where cert-manager is installed"
  value       = kubernetes_namespace.cert_manager.metadata[0].name
}

output "selfsigned_issuer_name" {
  description = "Name of the self-signed ClusterIssuer"
  value       = "selfsigned-issuer"
}

output "ca_issuer_name" {
  description = "Name of the CA ClusterIssuer"
  value       = "ca-issuer"
}

