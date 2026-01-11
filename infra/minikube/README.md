# Minikube Infrastructure

This directory contains Terraform configuration to deploy Kafka (Strimzi) and KOWL UI on a local minikube cluster.

## Prerequisites

1. **Minikube** installed and running:
   ```bash
   minikube start
   ```

2. **Terraform** >= 1.0 installed

3. **kubectl** configured to connect to minikube:
   ```bash
   kubectl config use-context minikube
   ```

4. **Helm** installed (optional, Terraform will use Helm provider)

## Structure

```
infra/minikube/
├── main.tf                 # Main Terraform configuration
├── variables.tf            # Input variables
├── outputs.tf              # Output values
├── terraform.tfvars.example # Example variable values
├── modules/
│   ├── strimzi/           # Strimzi Kafka Operator module
│   ├── kowl/              # KOWL UI module
│   └── postgres/          # PostgreSQL module
└── README.md              # This file
```

## Usage

1. **Initialize Terraform:**
   ```bash
   cd infra/minikube
   terraform init
   ```

2. **Review and customize variables (optional):**
   ```bash
   cp terraform.tfvars.example terraform.tfvars
   # Edit terraform.tfvars as needed
   ```

3. **Plan the deployment:**
   ```bash
   terraform plan
   ```

4. **Apply the configuration:**
   ```bash
   # Apply this first for CRDs
   terraform apply -target=module.strimzi.helm_release.strimzi_kafka_operator
   terraform apply
   ```

   This will:
   - Create a `kafka` namespace
   - Install Strimzi Kafka Operator via Helm
   - Deploy a Kafka cluster (1 broker, 1 Zookeeper)
   - Install KOWL UI via Helm
   - Create a `payments-platform` namespace
   - Deploy PostgreSQL via Bitnami Helm chart

5. **Get outputs:**
   ```bash
   terraform output
   ```

## What Gets Deployed

### Strimzi Kafka Operator
- Installs the Strimzi Kafka Operator via Helm
- Creates a Kafka cluster with:
  - 1 Kafka broker (suitable for local development)
  - 1 Zookeeper instance
  - Ephemeral storage (data is lost on pod restart)
  - Resource limits appropriate for minikube

### KOWL UI
- Installs KOWL (Kafka UI) via Helm
- Exposes UI on NodePort 30080
- Automatically configured to connect to the Kafka cluster

### PostgreSQL
- Installs PostgreSQL via Bitnami Helm chart
- Creates databases: `ledger_db` and `payments_db`
- Creates user `ledger_user` for ledger database
- Persistent storage (1Gi)
- Resource limits appropriate for minikube

## Accessing KOWL UI

After deployment, access KOWL UI at:
```bash
http://$(minikube ip):30080
```

Or manually:
```bash
MINIKUBE_IP=$(minikube ip)
echo "KOWL UI: http://$MINIKUBE_IP:30080"
```

## Kafka Bootstrap Servers

The Kafka bootstrap servers address is:
```
kafka-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092
```

Get it from Terraform outputs:
```bash
terraform output kafka_bootstrap_servers
```

For local development (outside Kubernetes), you can port-forward:
```bash
kubectl port-forward -n kafka svc/kafka-cluster-kafka-bootstrap 9092:9092
```

Then use: `localhost:9092`

## PostgreSQL Database

PostgreSQL is deployed via Terraform using the Bitnami Helm chart.

**Service Address:**
```
postgres-service.payments-platform.svc.cluster.local:5432
```

**Databases Created:**
- `ledger_db` (user: `ledger_user`, password: `ledger_password`)
- `payments_db` (user: `postgres`, password: from terraform.tfvars)

**Get PostgreSQL connection info:**
```bash
terraform output postgres_service_host
terraform output postgres_service_port
```

**Access PostgreSQL:**
```bash
# Port-forward for local access
kubectl port-forward -n payments-platform svc/postgres-service 5432:5432

# Connect
psql -h localhost -U postgres -d ledger_db
```

## PostgreSQL Database

PostgreSQL is deployed via Terraform and automatically:
- Creates `ledger_db` database with `ledger_user` user
- Creates `payments_db` database
- Exposes service at: `postgres-service.payments-platform.svc.cluster.local:5432`

## Payments Service Integration

The Payments Service will automatically register the following topics on startup:
- `payment.commands` (3 partitions)
- `payment.events` (3 partitions)
- `payment.retry` (3 partitions)

Configure the Payments Service to connect to Kafka:
```bash
export KAFKA_BOOTSTRAP_SERVERS=kafka-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092
```

Or for local development with port-forward:
```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

## Cleanup

To remove all resources:
```bash
terraform destroy
```

## Troubleshooting

### Kafka cluster not ready
If the Kafka cluster takes time to start, wait a few minutes and check:
```bash
kubectl get kafka -n kafka
kubectl get pods -n kafka
```

Wait for all pods to be in `Running` state:
```bash
kubectl wait --for=condition=ready pod -l strimzi.io/cluster=kafka-cluster -n kafka --timeout=300s
```

### KOWL UI not accessible
1. Check if KOWL pod is running:
   ```bash
   kubectl get pods -n kafka -l app.kubernetes.io/name=kowl
   ```

2. Check NodePort service:
   ```bash
   kubectl get svc -n kafka kowl
   ```

3. Get minikube IP:
   ```bash
   minikube ip
   ```

4. Verify NodePort is accessible:
   ```bash
   curl http://$(minikube ip):30080
   ```

### Port forwarding (alternative to NodePort)
If NodePort doesn't work, use port forwarding:
```bash
kubectl port-forward -n kafka svc/kowl 8080:8080
```
Then access at: http://localhost:8080

### Payments Service can't connect to Kafka
1. Verify Kafka is running:
   ```bash
   kubectl get pods -n kafka
   ```

2. Check if you need to port-forward:
   ```bash
   kubectl port-forward -n kafka svc/kafka-cluster-kafka-bootstrap 9092:9092
   ```

3. Test connection from Payments Service:
   ```bash
   # From within Kubernetes
   KAFKA_BOOTSTRAP_SERVERS=kafka-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092
   
   # From localhost (with port-forward)
   KAFKA_BOOTSTRAP_SERVERS=localhost:9092
   ```
