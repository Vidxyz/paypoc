output "bootstrap_servers" {
  description = "Kafka bootstrap servers address"
  value       = "kafka-cluster-kafka-bootstrap.${var.namespace}.svc.cluster.local:9092"
}

output "kafka_cluster_name" {
  description = "Name of the Kafka cluster"
  value       = "kafka-cluster"
}

# output "kafka_ready" {
#   description = "Indicates when Kafka cluster is ready"
#   value       = time_sleep.wait_for_kafka.id
# }

