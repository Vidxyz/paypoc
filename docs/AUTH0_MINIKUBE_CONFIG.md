# Auth0 Configuration for Minikube (auth.local)

This document provides the exact Auth0 Dashboard configuration values for your auth service running on minikube at `auth.local`.

---

## Auth0 Dashboard Configuration

When setting up your Auth0 Regular Web Application, use these values:

### 1. Application Login URL (Optional)

**Purpose**: Where Auth0 redirects users after login when no `redirect_uri` is specified.

**Value**:
```
http://auth.local
```

**Note**: This is optional since we always specify `redirect_uri` in our authorization requests. You can leave this empty or set it to `http://auth.local`.

---

### 2. Allowed Callback URLs (Required)

**Purpose**: URLs that Auth0 can redirect to after authentication. **Must match exactly** (including protocol, domain, port, and path).

**Values** (one per line):
```
http://auth.local/auth/callback
http://auth.local/auth/callback/pkce
http://localhost:9001/auth/callback
http://localhost:9001/auth/callback/pkce
```

**Explanation**:
- First two lines: For minikube access via `auth.local` (when DNS is configured on your machine)
- Last two lines: For local development (direct access to service on localhost)

**Important**: Include both `auth.local` and `localhost` versions to support both minikube and local development.

---

### 3. Allowed Logout URLs (Required)

**Purpose**: URLs that Auth0 can redirect to after logout.

**Values** (one per line):
```
http://auth.local
http://localhost:9001
```

**Explanation**:
- `http://auth.local`: Minikube access
- `http://localhost:9001`: Local development

**Note**: Auth0 will only redirect to these URLs after logout. Any other URL will be rejected.

---

### 4. Allowed Web Origins (Required for CORS)

**Purpose**: URLs that are allowed to make cross-origin requests to Auth0 (for silent authentication, token refresh, etc.).

**Values** (one per line):
```
http://auth.local
http://localhost:9001
```

**Explanation**:
- `http://auth.local`: Minikube access
- `http://localhost:9001`: Local development

**Note**: If you have a frontend application (e.g., at `frontend.local`), add that domain here too.

---

### 5. Allowed Origins (CORS) (Required)

**Purpose**: Similar to Allowed Web Origins, used for token refresh and other API calls.

**Values** (one per line):
```
http://auth.local
http://localhost:9001
```

---

### 6. Advanced Settings → Grant Types

Make sure these grant types are enabled:
- ✅ **Authorization Code** (required for login flow)
- ✅ **Refresh Token** (required for token refresh)
- ✅ **Implicit** (optional, only if you need it)

---

### 7. Advanced Settings → OAuth Settings

- **OAuth 2.0**: Enabled
- **OIDC Conformant**: Enabled (recommended)

---

## Complete Auth0 Dashboard Configuration Summary

**Location**: Auth0 Dashboard → Applications → Your Application → Settings

### Application URIs Section:

```
Application Login URL:
http://auth.local

Allowed Callback URLs:
http://auth.local/auth/callback
http://auth.local/auth/callback/pkce
http://localhost:9001/auth/callback
http://localhost:9001/auth/callback/pkce

Allowed Logout URLs:
http://auth.local
http://localhost:9001

Allowed Web Origins:
http://auth.local
http://localhost:9001

Allowed Origins (CORS):
http://auth.local
http://localhost:9001
```

---

## Kubernetes Configuration

Your Kubernetes secret (`kubernetes/auth/secret.yaml`) should have:

```yaml
stringData:
  AUTH0_DOMAIN: your-tenant.auth0.com  # Your Auth0 tenant domain
  AUTH0_CLIENT_ID: "your-client-id"     # From Auth0 Dashboard
  AUTH0_CLIENT_SECRET: "your-client-secret"  # From Auth0 Dashboard
  AUTH0_REDIRECT_URI: http://auth.local/auth/callback
  AUTH0_REDIRECT_URI_PKCE: http://auth.local/auth/callback/pkce
```

---

## DNS Configuration for Minikube

To make `auth.local` work on your machine, add this to your `/etc/hosts` file:

**macOS/Linux** (`/etc/hosts`):
```
127.0.0.1 auth.local
```

**Windows** (`C:\Windows\System32\drivers\etc\hosts`):
```
127.0.0.1 auth.local
```

**Get Minikube IP** (if using a different IP):
```bash
minikube ip
```

If minikube IP is different from 127.0.0.1, use that IP instead.

---

## Testing the Configuration

### 1. Verify Ingress is Working

```bash
# Apply the ingress
kubectl apply -f kubernetes/auth/ingress.yaml

# Check ingress
kubectl get ingress -n auth

# Test the service
curl http://auth.local/health
```

### 2. Test Auth0 Login Flow

1. **Navigate to login endpoint**:
   ```
   http://auth.local/auth/login
   ```

2. **Expected behavior**:
   - Browser redirects to Auth0 login page
   - After login, Auth0 redirects back to `http://auth.local/auth/callback?code=...`
   - Your auth service exchanges the code for tokens
   - Returns access token and refresh token

### 3. Verify Callback URL

If you see an Auth0 error like:
```
Invalid Callback URL: http://auth.local/auth/callback
```

**Fix**: Double-check that `http://auth.local/auth/callback` is **exactly** listed in "Allowed Callback URLs" in Auth0 Dashboard (case-sensitive, must include protocol).

---

## Production Configuration

For production, update the values to use HTTPS:

### Auth0 Dashboard:
```
Allowed Callback URLs:
https://auth.yourdomain.com/auth/callback
https://auth.yourdomain.com/auth/callback/pkce

Allowed Logout URLs:
https://auth.yourdomain.com

Allowed Web Origins:
https://auth.yourdomain.com
```

### Kubernetes Secret:
```yaml
AUTH0_REDIRECT_URI: https://auth.yourdomain.com/auth/callback
AUTH0_REDIRECT_URI_PKCE: https://auth.yourdomain.com/auth/callback/pkce
```

---

## Troubleshooting

### Issue: "Invalid Callback URL" error

**Cause**: Callback URL doesn't match exactly what's configured in Auth0.

**Solution**:
1. Check Auth0 Dashboard → Applications → Settings → Allowed Callback URLs
2. Ensure the URL matches **exactly** (including `http://`, domain, port if any, and path)
3. No trailing slashes unless explicitly included
4. Case-sensitive matching

### Issue: CORS errors in browser console

**Cause**: Origin not listed in "Allowed Web Origins".

**Solution**:
1. Add the origin (e.g., `http://auth.local`) to "Allowed Web Origins" in Auth0 Dashboard
2. Also add to "Allowed Origins (CORS)"

### Issue: Can't reach auth.local

**Cause**: DNS not configured or ingress not working.

**Solution**:
1. Verify `/etc/hosts` has `127.0.0.1 auth.local`
2. Check ingress: `kubectl get ingress -n auth`
3. Verify ingress controller is running: `kubectl get pods -n ingress-nginx`
4. Test service directly: `kubectl port-forward -n auth service/auth-service 9001:9001`, then access `http://localhost:9001`

---

## Quick Reference

| Setting | Value |
|---------|-------|
| **Application Login URL** | `http://auth.local` |
| **Allowed Callback URLs** | `http://auth.local/auth/callback`<br>`http://auth.local/auth/callback/pkce`<br>`http://localhost:9001/auth/callback`<br>`http://localhost:9001/auth/callback/pkce` |
| **Allowed Logout URLs** | `http://auth.local`<br>`http://localhost:9001` |
| **Allowed Web Origins** | `http://auth.local`<br>`http://localhost:9001` |
| **Internal Service Port** | `9001` |
| **Ingress Host** | `auth.local` |

---

## Next Steps

1. ✅ Configure Auth0 Dashboard with the values above
2. ✅ Update Kubernetes secrets with your Auth0 credentials
3. ✅ Apply the ingress: `kubectl apply -f kubernetes/auth/ingress.yaml`
4. ✅ Add `auth.local` to `/etc/hosts`
5. ✅ Test login flow: `http://auth.local/auth/login`

