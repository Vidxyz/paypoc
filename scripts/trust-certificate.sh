kubectl get secret ca-key-pair -n cert-manager -o jsonpath='{.data.tls\.crt}' | base64 -d > ca.crt
echo "🔐 Requesting sudo for adding certificate to keychain..."
sudo -v # Pre-validate sudo so it doesn't hang in the background
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ca.crt