terraform destroy -target=module.strimzi --auto-approve
terraform apply -target=module.strimzi --auto-approve

# Rollout restart every single service in parallel
kubectl rollout restart deployment/payments-service -n payments-platform &
kubectl rollout restart deployment/ledger-service -n payments-platform &
kubectl rollout restart deployment/inventory-service -n inventory
kubectl rollout restart deployment/cart-service -n cart &
kubectl rollout restart deployment/catalog-service -n catalog &
kubectl rollout restart deployment/order-service -n order &
kubectl rollout restart deployment/fulfillment-service -n fulfillment &
kubectl rollout restart deployment/user-service -n user &
kubectl rollout restart deployment/admin-console -n ui &
kubectl rollout restart deployment/frontend -n ui &
kubectl rollout restart deployment/seller-console -n ui