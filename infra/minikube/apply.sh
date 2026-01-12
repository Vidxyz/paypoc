terraform apply -target=module.strimzi.helm_release.strimzi_kafka_operator --auto-approve
terraform apply --target=module.cert_manager.helm_release.cert_manager --auto-approve
terraform apply --auto-approve