# Auth0 Setup Instructions

## Overview

Auth0 is a **managed SaaS service** (no infrastructure code needed). Unlike Zitadel, you don't deploy Auth0 in your cluster. Instead, you configure it via the Auth0 Dashboard and your services communicate with Auth0 over HTTPS.

---

## Step 1: Create Auth0 Account & Tenant

1. **Sign up for Auth0**: Go to https://auth0.com/signup
2. **Choose a tenant domain**: Your tenant will be `{your-tenant-name}.auth0.com`
   - Example: `payments-platform-dev.auth0.com`
   - Note this domain - you'll need it for configuration

---

## Step 2: Create a Regular Web Application (for Login)

1. **Navigate to Applications**: In Auth0 Dashboard → Applications → Applications
2. **Create Application**:
   - Click "+ Create Application"
   - Name: `payments-platform-web` (or any name)
   - Type: Select **"Regular Web Applications"**
   - Click "Create"

3. **Configure Application Settings**:
   - Go to the **Settings** tab
   - Note the following values (you'll need them later):
     - **Domain**: `your-tenant.auth0.com`
     - **Client ID**: Copy this value
     - **Client Secret**: Click "Show" and copy this value

4. **Configure Allowed Callback URLs**:
   - In Settings → Application URIs section
   - **Allowed Callback URLs**: Add your callback URLs:
     ```
     http://localhost:9001/auth/callback,http://localhost:9001/auth/callback/pkce
     ```
   - For production, also add:
     ```
     https://your-domain.com/auth/callback,https://your-domain.com/auth/callback/pkce
     ```

5. **Configure Allowed Logout URLs**:
   - **Allowed Logout URLs**: Add:
     ```
     http://localhost:9001,https://your-domain.com
     ```

6. **Configure Allowed Web Origins** (for CORS):
   - **Allowed Web Origins**: Add:
     ```
     http://localhost:9001,https://your-domain.com
     ```

7. **Save Changes**: Scroll to bottom and click "Save Changes"

---

## Step 3: Enable Management API & Create Machine-to-Machine Application

The Management API is needed for your `user-service` to create users programmatically.

1. **Navigate to APIs**: Auth0 Dashboard → Applications → APIs → Auth0 Management API
2. **Go to Machine to Machine Applications tab**
3. **Create Machine-to-Machine Application**:
   - Click "+ Create Application"
   - Name: `payments-platform-user-service`
   - Click "Create"

4. **Authorize the Application**:
   - After creation, you'll see a list of APIs
   - Find "Auth0 Management API"
   - Toggle it to **ON** (Authorized)

5. **Select Scopes**:
   - Click the arrow next to "Auth0 Management API"
   - Select the following scopes (permissions):
     - `create:users` - Create users
     - `read:users` - Read user information
     - `update:users` - Update user information (optional, if needed)
   - Click "Update"

6. **Get Management API Token** (Temporary - see Step 4 for automated approach):
   - **Note**: Management API tokens expire after 24 hours
   - For testing, you can generate a token manually:
     - Go to Machine-to-Machine Applications → `payments-platform-user-service`
     - Click "Test" tab
     - Click "Copy Token" to get a temporary token
   - For production, you'll need to implement token refresh (see Step 4)

---

## Step 4: Get Management API Token (Programmatic)

For production, you should obtain Management API tokens programmatically. Here's how:

1. **Get Client ID and Secret**:
   - Go to Applications → Machine to Machine Applications → `payments-platform-user-service`
   - Go to Settings tab
   - Copy **Client ID** and **Client Secret**

2. **Request Token** (POST request):
   ```bash
   curl --request POST \
     --url https://YOUR_TENANT.auth0.com/oauth/token \
     --header 'content-type: application/json' \
     --data '{
       "client_id": "YOUR_M2M_CLIENT_ID",
       "client_secret": "YOUR_M2M_CLIENT_SECRET",
       "audience": "https://YOUR_TENANT.auth0.com/api/v2/",
       "grant_type": "client_credentials"
     }'
   ```

3. **Response**:
   ```json
   {
     "access_token": "eyJhbGc...",
     "token_type": "Bearer",
     "expires_in": 86400
   }
   ```

4. **Use the `access_token`** as your Management API token (it expires in 24 hours, so you'll need to refresh it periodically)

---

## Step 5: Configure Your Services

### Update Kubernetes Secrets

1. **Auth Service Secrets** (`kubernetes/auth/secret.yaml`):
   ```yaml
   stringData:
     AUTH0_DOMAIN: your-tenant.auth0.com  # Replace with your tenant
     AUTH0_CLIENT_ID: "your-web-app-client-id"  # From Step 2
     AUTH0_CLIENT_SECRET: "your-web-app-client-secret"  # From Step 2
     AUTH0_REDIRECT_URI: "http://localhost:9001/auth/callback"
     AUTH0_REDIRECT_URI_PKCE: "http://localhost:9001/auth/callback/pkce"
   ```

2. **User Service Secrets** (`kubernetes/user/secret.yaml`):
   ```yaml
   stringData:
     AUTH0_DOMAIN: your-tenant.auth0.com  # Replace with your tenant
     AUTH0_MANAGEMENT_API_TOKEN: "your-management-api-token"  # From Step 3 or 4
   ```

   **Note**: For production, you should:
   - Implement token refresh in `Auth0UserService`
   - Store M2M client ID/secret in secrets
   - Automatically request new tokens before they expire

### Update Local Configuration (for development)

1. **Auth Service** (`services/auth/conf/application.conf`):
   ```hocon
   auth0 {
     domain = ${?AUTH0_DOMAIN}"your-tenant.auth0.com"
     client.id = ${?AUTH0_CLIENT_ID}""
     client.secret = ${?AUTH0_CLIENT_SECRET}""
     redirect.uri = ${?AUTH0_REDIRECT_URI}"http://localhost:9001/auth/callback"
     redirect.uri.pkce = ${?AUTH0_REDIRECT_URI_PKCE}"http://localhost:9001/auth/callback/pkce"
   }
   ```

2. **User Service** (`services/user/conf/application.conf`):
   ```hocon
   auth0 {
     domain = ${?AUTH0_DOMAIN}"your-tenant.auth0.com"
     management.api.token = ${?AUTH0_MANAGEMENT_API_TOKEN}""
   }
   ```

---

## Step 6: Implement Missing Code (TODO Items)

### 6.1: Complete Auth0Service Token Exchange

The `Auth0Service.exchangeCodeForTokens()` method needs to be implemented. Here's what it should do:

```scala
def exchangeCodeForTokens(code: String): Future[Auth0Tokens] = {
  val tokenRequest = new TokenRequest(
    new URI(tokenEndpoint),
    new ClientSecretBasic(new ClientID(clientId), new Secret(clientSecret)),
    new AuthorizationCodeGrant(new AuthorizationCode(code), new URI(redirectUri))
  )

  ws.url(tokenEndpoint)
    .withHttpHeaders("Content-Type" -> "application/json")
    .post(Json.obj(
      "grant_type" -> "authorization_code",
      "client_id" -> clientId,
      "client_secret" -> clientSecret,
      "code" -> code,
      "redirect_uri" -> redirectUri
    ))
    .map { response =>
      if (response.status == 200) {
        val json = response.json
        Auth0Tokens(
          idToken = (json \ "id_token").as[String],
          accessToken = (json \ "access_token").as[String],
          refreshToken = (json \ "refresh_token").asOpt[String].getOrElse("")
        )
      } else {
        throw new RuntimeException(s"Token exchange failed: ${response.status} - ${response.body}")
      }
    }
}
```

### 6.2: Implement JWT Parsing to Extract User Claims

In `AuthController.callback`, you need to parse the ID token to extract:
- Email
- Auth0 User ID (`sub` claim)

You can use a JWT library like `pdi.jwt` or `nimbus-jose-jwt` to decode and validate the ID token.

### 6.3: Implement Auth0 Logout

The `Auth0Service.logout()` method should redirect to Auth0's logout endpoint:

```scala
def logout(returnTo: String): Future[String] = {
  Future.successful(
    s"https://$domain/v2/logout?client_id=$clientId&returnTo=$returnTo"
  )
}
```

---

## Step 7: User Signup & Login Flow

### Flow Overview:

1. **User Signup**:
   - Frontend/API calls `POST /users/signup` (user-service)
   - User service creates user in Auth0 via Management API → Gets `auth0_user_id`
   - User service saves user to PostgreSQL with `auth0_user_id`
   - User service publishes `user.created` event (for ledger account creation)
   - Response: User created (but not logged in yet)

2. **User Login**:
   - Frontend redirects to `GET /auth/login` (auth-service)
   - Auth service redirects to Auth0 login page
   - User enters credentials on Auth0
   - Auth0 redirects to `GET /auth/callback?code=...` (auth-service)
   - Auth service exchanges code for tokens
   - Auth service parses ID token to get email and `auth0_user_id`
   - Auth service looks up user in database by email or `auth0_user_id`
   - Auth service merges claims (account_type, etc.) and creates enhanced JWT
   - Auth service stores refresh token in database
   - Response: Returns access token and refresh token

3. **Token Refresh**:
   - Client sends refresh token to `POST /auth/refresh`
   - Auth service validates refresh token from database
   - Auth service retrieves user claims and issues new access token
   - Response: New access token

---

## Step 8: Testing the Setup

### 8.1: Test User Creation

```bash
# Create a user via user-service API
curl -X POST http://localhost:9000/users/signup \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Test123!",
    "firstname": "Test",
    "lastname": "User",
    "accountType": "BUYER"
  }'

# Verify user was created in Auth0 Dashboard
# Go to: User Management → Users → Search for "test@example.com"
```

### 8.2: Test Login Flow

1. **Initiate Login**:
   ```bash
   # Redirect to Auth0 login
   curl -L http://localhost:9001/auth/login
   ```

2. **After Auth0 login, Auth0 redirects to callback**
   - The callback should exchange code for tokens
   - Should return access token and refresh token

### 8.3: Test Token Validation

```bash
# Use the access token from login
curl -X GET http://localhost:9001/auth/me \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

---

## Step 9: Production Considerations

1. **Use PKCE Flow**: Implement PKCE for better security (especially for public clients)
2. **Token Refresh**: Implement automatic Management API token refresh
3. **Error Handling**: Add comprehensive error handling for Auth0 API failures
4. **Rate Limiting**: Be aware of Auth0 rate limits for Management API
5. **Custom Claims**: Configure Auth0 Actions/Rules to add custom claims (account_type) to tokens
6. **Environment Variables**: Use Kubernetes secrets properly (not hardcoded values)
7. **Monitoring**: Add logging and monitoring for Auth0 API calls
8. **HTTPS**: Ensure all callbacks use HTTPS in production

---

## Troubleshooting

### "Invalid redirect_uri"
- **Issue**: Callback URL doesn't match what's configured in Auth0
- **Fix**: Check Allowed Callback URLs in Auth0 Dashboard match exactly (including protocol, port, path)

### "Invalid client_id or client_secret"
- **Issue**: Wrong credentials in configuration
- **Fix**: Verify Client ID and Client Secret in secrets match Auth0 Dashboard

### "Management API token expired"
- **Issue**: M2M tokens expire after 24 hours
- **Fix**: Implement token refresh or use long-lived tokens (not recommended for production)

### "User not found after login"
- **Issue**: User exists in Auth0 but not in your database
- **Fix**: Ensure user signup flow creates user in both Auth0 and your database

---

## Next Steps

1. ✅ Complete the TODO items in `Auth0Service` (token exchange, logout)
2. ✅ Implement JWT parsing in `AuthController`
3. ✅ Add error handling and logging
4. ✅ Implement Management API token refresh in `Auth0UserService`
5. ✅ Add integration tests for Auth0 flows
6. ✅ Configure Auth0 Actions to add custom claims (account_type) to tokens
7. ✅ Set up monitoring and alerts for Auth0 API calls

---

## Resources

- [Auth0 Documentation](https://auth0.com/docs)
- [Auth0 Management API](https://auth0.com/docs/api/management/v2)
- [Auth0 OIDC/OAuth Flow](https://auth0.com/docs/get-started/authentication-and-authorization-flow)
- [Auth0 Actions (for custom claims)](https://auth0.com/docs/customize/actions)

