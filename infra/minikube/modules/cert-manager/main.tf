terraform {
  required_version = ">= 1.0"
}

# Deploy cert-manager using Helm
resource "helm_release" "cert_manager" {
  name       = "cert-manager"
  repository = "https://charts.jetstack.io"
  chart      = "cert-manager"
  version    = "v1.13.3"
  namespace  = "cert-manager"
  create_namespace = true

  set {
    name  = "installCRDs"
    value = "true"
  }

  set {
    name  = "prometheus.enabled"
    value = "false"
  }

  set {
    name  = "webhook.timeoutSeconds"
    value = "30"
  }

  depends_on = [
    kubernetes_namespace.cert_manager
  ]
}

# Create cert-manager namespace
resource "kubernetes_namespace" "cert_manager" {
  metadata {
    name = "cert-manager"
    labels = {
      app = "cert-manager"
    }
  }
}

# Wait for cert-manager to be ready
resource "time_sleep" "wait_for_cert_manager" {
  depends_on = [helm_release.cert_manager]
  create_duration = "60s"
}

# Create self-signed ClusterIssuer for development
resource "kubernetes_manifest" "selfsigned_cluster_issuer" {
  manifest = {
    apiVersion = "cert-manager.io/v1"
    kind       = "ClusterIssuer"
    metadata = {
      name = "selfsigned-issuer"
    }
    spec = {
      selfSigned = {}
    }
  }

  depends_on = [
    time_sleep.wait_for_cert_manager
  ]
}

# Create CA ClusterIssuer (for creating CA certificates)
resource "kubernetes_manifest" "ca_cluster_issuer" {
  manifest = {
    apiVersion = "cert-manager.io/v1"
    kind       = "ClusterIssuer"
    metadata = {
      name = "ca-issuer"
    }
    spec = {
      ca = {
        secretName = "ca-key-pair"
      }
    }
  }

  depends_on = [
    time_sleep.wait_for_cert_manager,
    kubernetes_manifest.ca_key_pair_certificate
  ]
}

# Create CA key pair Certificate (used by ca-issuer)
resource "kubernetes_manifest" "ca_key_pair_certificate" {
  manifest = {
    apiVersion = "cert-manager.io/v1"
    kind       = "Certificate"
    metadata = {
      name      = "ca-key-pair"
      namespace = "cert-manager"
    }
    spec = {
      isCA       = true
      commonName = "ca"
      secretName = "ca-key-pair"
      privateKey = {
        algorithm = "RSA"
        size      = 2048
      }
      issuerRef = {
        name = "selfsigned-issuer"
        kind = "ClusterIssuer"
      }
    }
  }

  depends_on = [
    time_sleep.wait_for_cert_manager
  ]
}

