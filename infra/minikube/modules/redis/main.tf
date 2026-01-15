# Deploy Redis using Bitnami Helm chart
resource "helm_release" "redis" {
  name       = "redis"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "redis"
  version    = var.chart_version != "" ? var.chart_version : null
  namespace  = var.namespace
  wait       = true
  timeout    = 600

  set {
    name  = "architecture"
    value = "standalone"
  }

  set {
    name  = "auth.enabled"
    value = var.redis_password != "" ? true : false
  }

  dynamic "set" {
    for_each = var.redis_password != "" ? [1] : []
    content {
      name  = "auth.password"
      value = var.redis_password
    }
  }

  set {
    name  = "master.persistence.enabled"
    value = true
  }

  set {
    name  = "master.persistence.size"
    value = "256Mi"
  }

  set {
    name  = "master.persistence.storageClass"
    value = "standard"
  }

  set {
    name  = "master.resources.requests.memory"
    value = var.redis_memory_limit
  }

  set {
    name  = "master.resources.requests.cpu"
    value = "200m"
  }

  set {
    name  = "master.resources.limits.memory"
    value = var.redis_memory_limit
  }

  set {
    name  = "master.resources.limits.cpu"
    value = var.redis_cpu_limit
  }

  set {
    name  = "metrics.enabled"
    value = false
  }

  # Override image to use official Redis image since Bitnami images are deprecated
  # Using official Redis image from Docker Hub
  # For standalone architecture, we need to set master.image.*
  set {
    name  = "master.image.registry"
    value = "docker.io"
  }

  set {
    name  = "master.image.repository"
    value = "redis"
  }

  set {
    name  = "master.image.tag"
    value = var.redis_image_tag != "" ? var.redis_image_tag : "7.2"
  }

  # Also set global image settings as fallback
  set {
    name  = "image.registry"
    value = "docker.io"
  }

  set {
    name  = "image.repository"
    value = "redis"
  }

  set {
    name  = "image.tag"
    value = var.redis_image_tag != "" ? var.redis_image_tag : "7.2"
  }
}

# Create a simpler service name alias for easier reference
resource "kubernetes_service" "redis_alias" {
  metadata {
    name      = "redis-service"
    namespace = var.namespace
    labels = {
      app = "redis"
    }
  }
  
  spec {
    type = "ClusterIP"
    port {
      port        = 6379
      target_port = 6379
      protocol    = "TCP"
      name        = "redis"
    }
    
    selector = {
      "app.kubernetes.io/name"     = "redis"
      "app.kubernetes.io/instance" = "redis"
    }
  }
  
  depends_on = [helm_release.redis]
}

# Wait for Redis pod to be ready and accepting connections
resource "null_resource" "wait_for_redis" {
  depends_on = [helm_release.redis, kubernetes_service.redis_alias]
  
  provisioner "local-exec" {
    command = <<-EOT
      set -e
      echo "Waiting for Redis pod to be ready..."
      kubectl wait --for=condition=ready pod \
        -l app.kubernetes.io/name=redis,app.kubernetes.io/instance=redis \
        -n ${var.namespace} \
        --timeout=300s
      
      echo "Redis is ready and accepting connections"
    EOT
  }
  
  triggers = {
    redis_release = helm_release.redis.id
    redis_service = kubernetes_service.redis_alias.id
  }
}

