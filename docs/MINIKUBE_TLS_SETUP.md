# Minikube TLS Setup Guide

This guide explains how to set up TLS certificates for your Minikube cluster using cert-manager with self-signed certificates.

## Overview

Since you're using custom DNS domains (`.local` TLDs like `auth.local`, `user.local`, `buyit.local`), Let's Encrypt won't work. Instead, we use cert-manager with a self-signed CA issuer to generate certificates for development.

## Prerequisites

1. Minikube running
2. DNS entries pointing to Minikube IP (e.g., `/etc/hosts` entries)
3. Terraform installed
4. Helm installed (for cert-manager deployment)

## Step 1: Deploy cert-manager

The cert-manager module is automatically deployed when you run `terraform apply` in the `infra/minikube` directory. It will:

1. Create the `cert-manager` namespace
2. Install cert-manager via Helm
3. Create a self-signed ClusterIssuer
4. Create a CA ClusterIssuer for issuing certificates signed by the CA

### Deploy Infrastructure

```bash
cd infra/minikube
terraform init
terraform apply
```

Wait for cert-manager to be ready (this may take 1-2 minutes):

```bash
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=cert-manager -n cert-manager --timeout=300s
```

## Step 2: Verify cert-manager is running

```bash
kubectl get pods -n cert-manager
kubectl get clusterissuer
```

You should see:
- `selfsigned-issuer` - for self-signed certificates
- `ca-issuer` - for CA-signed certificates (used by our ingress resources)

## Step 3: Update DNS entries

Make sure your `/etc/hosts` file includes entries pointing to Minikube:

```bash
# Get Minikube IP
MINIKUBE_IP=$(minikube ip)

# Add to /etc/hosts (requires sudo)
echo "$MINIKUBE_IP auth.local" | sudo tee -a /etc/hosts
echo "$MINIKUBE_IP user.local" | sudo tee -a /etc/hosts
echo "$MINIKUBE_IP buyit.local" | sudo tee -a /etc/hosts
```

## Step 4: Deploy services with TLS ingress

The ingress resources have been updated to:
1. Use `cert-manager.io/cluster-issuer: "ca-issuer"` annotation
2. Include TLS configuration with secret names
3. Enable SSL redirect

Deploy your services:

```bash
# Deploy auth service
kubectl apply -f kubernetes/auth/

# Deploy user service
kubectl apply -f kubernetes/user/

# Deploy frontend
kubectl apply -f kubernetes/frontend/

# ... deploy other services
```

## Step 5: Trust the CA certificate (Important!)

Since we're using self-signed certificates, your browser/HTTP client won't trust them by default. You need to trust the CA certificate.

### Option 1: Trust the CA certificate in your system (Recommended for browsers)

1. Get the CA certificate:

```bash
kubectl get secret ca-key-pair -n cert-manager -o jsonpath='{.data.tls\.crt}' | base64 -d > ca.crt
```

2. Trust the certificate (macOS):

```bash
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ca.crt
```

3. Trust the certificate (Linux):

```bash
sudo cp ca.crt /usr/local/share/ca-certificates/minikube-ca.crt
sudo update-ca-certificates
```

4. Trust the certificate (Windows):
   - Double-click `ca.crt`
   - Click "Install Certificate"
   - Choose "Trusted Root Certification Authorities"
   - Click "Finish"

### Option 2: Use `--insecure` flag (For curl/testing)

```bash
curl -k https://auth.local
```

### Option 3: Trust CA for Java applications

For your Java/Scala services connecting to Auth0, you can add the CA to Java's truststore:

```bash
# Get the CA certificate
kubectl get secret ca-key-pair -n cert-manager -o jsonpath='{.data.tls\.crt}' | base64 -d > ca.crt

# Import into Java truststore (if you have access to the Java runtime)
keytool -import -trustcacerts -file ca.crt -alias minikube-ca -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -noprompt
```

## Step 6: Verify certificates are issued

Check that certificates have been created:

```bash
kubectl get certificates --all-namespaces
kubectl get certificaterequests --all-namespaces
```

You should see certificates for:
- `auth-local-tls` in `auth` namespace
- `user-local-tls` in `user` namespace
- `buyit-local-tls` in `frontend` namespace (or wherever your frontend is)

Check certificate status:

```bash
kubectl describe certificate auth-local-tls -n auth
```

## Step 7: Test HTTPS endpoints

Once certificates are issued, test your endpoints:

```bash
# Test auth service
curl -k https://auth.local

# Test user service
curl -k https://user.local

# Test frontend
curl -k https://buyit.local
```

Or in your browser (after trusting the CA):
- https://auth.local
- https://user.local
- https://buyit.local

## Troubleshooting

### Certificates not being issued

1. Check cert-manager logs:

```bash
kubectl logs -n cert-manager deployment/cert-manager
kubectl logs -n cert-manager deployment/cert-manager-webhook
```

2. Check CertificateRequest status:

```bash
kubectl get certificaterequests -n auth
kubectl describe certificaterequest <request-name> -n auth
```

3. Verify ClusterIssuer exists:

```bash
kubectl get clusterissuer
kubectl describe clusterissuer ca-issuer
```

### SSL handshake errors in services

If your services (like `user-service`) still have SSL handshake errors when connecting to Auth0:

1. **This setup only fixes TLS for your ingress (auth.local, user.local, etc.)**
2. **It does NOT fix SSL issues with external services like Auth0**

For Auth0 connections, you have two options:
- Use `play.ws.ssl.loose.acceptAnyCertificate = true` in development (already configured)
- Ensure Auth0's certificates are trusted (they use Let's Encrypt, which should be trusted by default)

### Certificate renewal

Self-signed certificates don't expire quickly, but cert-manager will automatically renew them when they're close to expiration. You can check certificate expiry:

```bash
kubectl get certificate auth-local-tls -n auth -o jsonpath='{.status.notAfter}'
```

## Cleanup

To remove cert-manager:

```bash
cd infra/minikube
terraform destroy -target=module.cert_manager
```

Or remove manually:

```bash
helm uninstall cert-manager -n cert-manager
kubectl delete namespace cert-manager
```

