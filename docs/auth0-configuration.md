# Auth0 Configuration Guide

This guide explains how to configure Auth0 to work with the payments platform. The platform uses **separate Auth0 applications** for each console (frontend, admin-console, seller-console) to provide better security and user experience.

## Overview

The architecture uses:
- **Separate Auth0 Applications**: One for each console (frontend, admin-console, seller-console)
- **Shared Auth0 Tenant**: All applications share the same Auth0 domain and user database
- **Frontend**: Direct integration with Auth0 using `@auth0/auth0-spa-js` SDK (for BUYER accounts)
- **Admin Console**: Separate Auth0 application (for ADMIN accounts only)
- **Seller Console**: Separate Auth0 application (for SELLER accounts only) - Future
- **Microservices**: Validate Auth0 JWT tokens using Auth0's JWKS endpoint
- **Custom Claims**: User information (user_id, account_type, etc.) added to tokens via Auth0 Actions

## Step 1: Create Separate Auth0 Applications

You need to create **three separate Auth0 applications** (or two if seller-console isn't ready yet):

### 1.1: Frontend Application (for BUYER accounts)

1. **Log into Auth0 Dashboard**: https://manage.auth0.com/
2. **Navigate to Applications** → **Create Application**
3. **Configure Application Settings**:
   - **Name**: "BuyIt Frontend" or "Payments Platform Frontend"
   - **Application Type**: Single Page Application
   - **Allowed Callback URLs**: 
     - `http://localhost:5173`
     - `http://localhost:3000`
     - `https://buyit.local`
     - `http://localhost:5173/` (with trailing slash - add both)
     - `https://buyit.local/` (with trailing slash - add both)
   - **Allowed Logout URLs**:
     - `http://localhost:5173`
     - `http://localhost:3000`
     - `https://buyit.local`
     - `http://localhost:5173/` (with trailing slash)
     - `https://buyit.local/` (with trailing slash)
   - **Allowed Web Origins**: 
     - `http://localhost:5173`
     - `http://localhost:3000`
     - `https://buyit.local`
   - **Allowed Origins (CORS)**: (same as above)
   - **OAuth Settings**:
     - ✅ **OIDC Conformant**: Enabled (default)
     - ✅ **Refresh Token**: Enabled (if you want refresh tokens)
     - **Token Endpoint Authentication Method**: `none` (for SPAs using PKCE) - **CRITICAL**: Must be `none` for SPAs
     - **Grant Types**: 
       - ✅ **Authorization Code** (required)
       - ✅ **Refresh Token** (if using refresh tokens)
     - ✅ **PKCE**: Enabled (required for SPAs)

4. **Note down**:
   - **Client ID**: e.g., `LejLdidlSpBGICwXoUhxNjdHib59SUMr` → This is your `AUTH0_FRONTEND_CLIENT_ID`

### 1.2: Admin Console Application (for ADMIN accounts only)

1. **Navigate to Applications** → **Create Application**
2. **Configure Application Settings**:
   - **Name**: "BuyIt Admin Console" or "Payments Platform Admin Console"
   - **Application Type**: Single Page Application
   - **Allowed Callback URLs**: 
     - `https://admin.local`
     - `https://admin.local/` (with trailing slash)
   - **Allowed Logout URLs**:
     - `https://admin.local`
     - `https://admin.local/` (with trailing slash)
   - **Allowed Web Origins**: 
     - `https://admin.local`
   - **Allowed Origins (CORS)**: (same as above)
   - **OAuth Settings**: (same as frontend application)

3. **Note down**:
   - **Client ID**: e.g., `AdminConsoleClientId123` → This is your `AUTH0_ADMIN_CLIENT_ID`

### 1.3: Seller Console Application (for SELLER accounts only) - Future

1. **Navigate to Applications** → **Create Application**
2. **Configure Application Settings**:
   - **Name**: "BuyIt Seller Console" or "Payments Platform Seller Console"
   - **Application Type**: Single Page Application
   - **Allowed Callback URLs**: 
     - `https://seller.local`
     - `https://seller.local/` (with trailing slash)
   - **Allowed Logout URLs**:
     - `https://seller.local`
     - `https://seller.local/` (with trailing slash)
   - **Allowed Web Origins**: 
     - `https://seller.local`
   - **OAuth Settings**: (same as frontend application)

3. **Note down**:
   - **Client ID**: e.g., `SellerConsoleClientId123` → This is your `AUTH0_SELLER_CLIENT_ID` (for future use)

### 1.4: Shared Auth0 Domain

All applications share the same Auth0 tenant/domain:
- **Domain**: e.g., `dev-bm52noc2kc3fce8w.us.auth0.com` → This is your `AUTH0_DOMAIN`

## Step 3: Configure Auth0 API (Required for Access Tokens)

If you want to use access tokens (instead of ID tokens), you need to create and configure an Auth0 API:

1. **Navigate to Auth0 Dashboard** → **APIs** → **Create API**
2. **Configure API Settings**:
   - **Name**: e.g., "BuyIt API" or "Payments Platform API"
   - **Identifier**: This is your **API Audience** (e.g., `https://buyit.local/api` or `https://api.buyit.local`)
     - ⚠️ **IMPORTANT**: This identifier will be used as the `AUTH0_AUDIENCE` environment variable
   - **Signing Algorithm**: **RS256** (default - this ensures signed JWT tokens, not encrypted)
3. **Save the API**
4. **Configure Token Settings** (CRITICAL):
   - Go to **Settings** tab of your API
   - Scroll to **Token Settings**
   - **Disable "JSON Web Encryption (JWE)"** if it's enabled
   - Ensure **"Token Endpoint Authentication Method"** is set appropriately
   - ⚠️ **CRITICAL**: The API must be configured to issue **signed JWT tokens**, NOT encrypted tokens
   - If JWE is enabled, tokens will be encrypted and cannot be decoded/validated by your microservices
5. **Authorize the Application**:
   - Go to **APIs** → Your API → **Machine to Machine Applications** tab (if using M2M)
   - Or ensure your SPA application is authorized to request tokens for this API
   - For SPAs, the authorization happens automatically when you request the audience

6. **Note down the API Identifier** (this is your audience):
   - This will be used in `AUTH0_FRONTEND_AUDIENCE` and `AUTH0_ADMIN_AUDIENCE` environment variables
   - Example: `https://buyit.local/api`

## Step 4: Create Auth0 Action to Add Custom Claims

Custom claims are needed so microservices can extract user information (user_id, account_type) from JWT tokens without querying the user service.

**How it works:**
- When a user signs up through your user service, the service automatically stores user metadata in Auth0's `app_metadata` via the Management API
- This Action reads from `app_metadata` (no external API calls needed - Auth0 Actions can't access internal services)
- The metadata is then added as custom claims to both ID and access tokens
- This approach is more reliable and faster than calling your user service from Auth0 Actions

**Note for existing users:**
- Users created before this implementation won't have `app_metadata` set
- You can either:
  1. Have them sign up again through your user service (which will set metadata)
  2. Create a migration script to update existing users' metadata via Auth0 Management API
  3. The Action will gracefully skip adding claims if metadata is missing (user can still login)

1. **Navigate to Auth0 Dashboard** → **Actions** → **Flows** → **Login**
2. **Click "+" to add a new Action** → **Build Custom**
3. **Name the Action**: "Add Custom Claims"
4. **Select Trigger**: "Login / Post Login"
5. **Add the following code**:

```javascript
/**
 * Handler that will be called during the execution of a PostLogin flow.
 *
 * This Action reads user metadata from Auth0's app_metadata (stored when user signs up)
 * and adds custom claims to both ID and access tokens.
 *
 * @param {Event} event - Details about the user and the context in which they are logging in.
 * @param {PostLoginAPI} api - Interface whose methods can be used to change the behavior of the login.
 */
exports.onExecutePostLogin = async (event, api) => {
  const namespace = 'https://buyit.local/';
  
  // Read from app_metadata (stored in Auth0 when user was created via user service)
  // This avoids external API calls since Auth0 Actions can't access internal services
  const appMetadata = event.user.app_metadata || {};
  // Note: Using "app_user_id" instead of "user_id" and "app_email" instead of "email" 
  // to avoid Auth0 property name conflicts (Auth0 reserves these property names)
  const userId = appMetadata.app_user_id;
  const auth0UserId = appMetadata.auth0_user_id || event.user.user_id;
  const email = appMetadata.app_email || event.user.email; // Fallback to event.user.email
  const accountType = appMetadata.account_type;
  const firstname = appMetadata.firstname || event.user.given_name;
  const lastname = appMetadata.lastname || event.user.family_name;
  
  console.log(`[Add Custom Claims] Reading from app_metadata for user: ${event.user.email}`);
  console.log(`[Add Custom Claims] Metadata:`, {
    userId,
    auth0UserId,
    email,
    accountType,
    firstname,
    lastname,
    hasMetadata: !!appMetadata.app_user_id,
    fullMetadata: JSON.stringify(appMetadata)
  });
  
  if (!userId || !accountType) {
    console.warn(`[Add Custom Claims] Missing required metadata: userId=${userId}, accountType=${accountType}`);
    console.warn(`[Add Custom Claims] User may need to sign up through your user service first to populate metadata`);
    console.warn(`[Add Custom Claims] Or metadata may need to be updated for existing users`);
    // Don't add claims if metadata is missing - user might not exist in your system yet
    // or metadata wasn't set during signup
    return;
  }
  
  console.log(`[Add Custom Claims] Adding claims: user_id=${userId}, auth0_user_id=${auth0UserId}, email=${email}, account_type=${accountType}`);
  
  // Add custom claims to ID token and access token
  api.idToken.setCustomClaim(`${namespace}user_id`, userId);
  api.idToken.setCustomClaim(`${namespace}auth0_user_id`, auth0UserId);
  api.idToken.setCustomClaim(`${namespace}email`, email);
  api.idToken.setCustomClaim(`${namespace}account_type`, accountType);
  if (firstname) {
    api.idToken.setCustomClaim(`${namespace}firstname`, firstname);
  }
  if (lastname) {
    api.idToken.setCustomClaim(`${namespace}lastname`, lastname);
  }
  
  // Also add to access token (for API calls)
  api.accessToken.setCustomClaim(`${namespace}user_id`, userId);
  api.accessToken.setCustomClaim(`${namespace}auth0_user_id`, auth0UserId);
  api.accessToken.setCustomClaim(`${namespace}email`, email);
  api.accessToken.setCustomClaim(`${namespace}account_type`, accountType);
  if (firstname) {
    api.accessToken.setCustomClaim(`${namespace}firstname`, firstname);
  }
  if (lastname) {
    api.accessToken.setCustomClaim(`${namespace}lastname`, lastname);
  }
  
  console.log(`[Add Custom Claims] Claims added successfully to both ID and access tokens`);
};
```

6. **Configure Secrets** (if needed):
   - Click on the Action → **Settings** → **Secrets**
   - Add `USER_SERVICE_URL` if your user service URL is different from default

7. **Deploy the Action**: 
   - Click **"Deploy"** button (top right) - **CRITICAL**: Action must be deployed to run
   - Wait for deployment confirmation

8. **Add Action to Login Flow** (CRITICAL - Action won't run if not in flow):
   - Go to **Actions** → **Flows** → **Login**
   - You should see a visual flow editor
   - On the right side, click the **"Custom"** tab (not "Built-in")
   - Find your "Add Custom Claims" Action in the list
   - **Drag and drop** it into the flow between "Start" and "Complete"
   - The Action should appear as a box in the flow
   - Click **"Apply"** button to save the flow changes
   - ⚠️ **VERIFY**: The Action box should be visible in the flow diagram

9. **Verify Action is Running**:
   - Go to **Monitoring** → **Logs**
   - Filter by log type: **"Success Login"** or **"Success Exchange"**
   - Look for logs with your user's login
   - Click on a log entry → **"Action Details"** tab
   - You should see console output from your Action (prefixed with `[Add Custom Claims]`)
   - If you don't see Action Details, the Action is not running

10. **Troubleshooting - Action Not Running**:
    
    **If you see NO logs at all from your Action:**
    
    a. **Verify Action is in Flow**:
       - Go to **Actions** → **Flows** → **Login**
       - Check if your Action appears in the flow diagram
       - If not, drag it in and click **"Apply"**
    
    b. **Verify Action is Deployed**:
       - Go to **Actions** → **Library**
       - Find your "Add Custom Claims" Action
       - Check if it shows "Deployed" status
       - If it shows "Draft", click **"Deploy"**
    
    c. **Test with Simple Logging Action** (to verify Actions work at all):
       - Create a new Action with this minimal code:
       ```javascript
       exports.onExecutePostLogin = async (event, api) => {
         console.log('TEST ACTION RUNNING - User:', event.user.email);
         console.log('TEST ACTION - Auth0 User ID:', event.user.user_id);
       };
       ```
       - Deploy it
       - Add it to Login flow
       - Log in and check logs
       - If this doesn't log, Actions aren't running (check flow configuration)
    
    d. **Check Log Filtering**:
       - In **Monitoring** → **Logs**, make sure filters aren't hiding Action logs
       - Try filtering by: **"Action Details"** or **"Success Login"**
       - Look for logs with type code **"s"** (success) or **"slo"** (successful logout)
    
    e. **Verify You're Logging In Correctly**:
       - Make sure you're actually going through the Auth0 login flow
       - Check that login is successful (not using cached session)
       - Try logging out and logging in again
    
    f. **Check Action Execution Order**:
       - In the Login flow, make sure your Action is positioned correctly
       - It should be after authentication but before token issuance
       - Try moving it to different positions in the flow

## Step 5: Test Custom Claims

1. **Check Auth0 Logs First**:
   - Go to **Monitoring** → **Logs**
   - Find your most recent successful login
   - Click on the log entry
   - Open the **"Action Details"** tab
   - You should see console logs from your Action (e.g., `[Add Custom Claims] Fetching user...`)
   - If Action Details tab is missing or empty, the Action is not running (see Troubleshooting above)

2. **Log in through your frontend**

3. **Inspect the JWT token** (decode it on jwt.io or in browser console):
   - Copy the token from `localStorage.getItem('bearerToken')`
   - Paste it into jwt.io
   - Look in the "Payload" section for your custom claims

4. **Verify custom claims are present**:
   - `https://buyit.local/user_id`: Should contain the UUID
   - `https://buyit.local/account_type`: Should be BUYER, SELLER, or ADMIN
   - `https://buyit.local/firstname`: User's first name (optional)
   - `https://buyit.local/lastname`: User's last name (optional)

5. **If claims are missing**:
   - Check Action Details in Auth0 logs for errors
   - Verify the user exists in your database with correct `auth0_user_id`
   - Test the endpoint manually: `https://user.local/users/by-auth0-id/{auth0_user_id}`
   - Check that the user service is accessible from the internet (Auth0 Actions can't access internal URLs)

## Step 6: Configure Environment Variables

The platform now uses a centralized `.env` file for all environment variables. This makes it easier to manage credentials across all services.

### Setup Instructions

1. **Copy the example file**:
   ```bash
   cp scripts/.env.example scripts/.env
   ```

2. **Edit `scripts/.env`** with your actual Auth0 credentials:
   ```bash
   # Required Auth0 variables
   AUTH0_DOMAIN=dev-bm52noc2kc3fce8w.us.auth0.com
   AUTH0_FRONTEND_CLIENT_ID=your-frontend-client-id
   AUTH0_ADMIN_CLIENT_ID=your-admin-console-client-id
   AUTH0_FRONTEND_REDIRECT_URI=https://buyit.local
   AUTH0_ADMIN_REDIRECT_URI=https://admin.local
   AUTH0_FRONTEND_AUDIENCE=https://buyit.local/api
   AUTH0_ADMIN_AUDIENCE=https://buyit.local/api
   ```

3. **The `deploy.sh` script automatically loads variables from `.env`**:
   - The script sources `scripts/.env` at startup
   - If `.env` doesn't exist, it will warn you but continue with shell environment variables
   - The `.env` file is gitignored for security

### Legacy: Manual Environment Variable Setup (Not Recommended)

If you prefer to set environment variables manually instead of using `.env`, you can export them before running `deploy.sh`:

```bash
export AUTH0_DOMAIN=dev-bm52noc2kc3fce8w.us.auth0.com
export AUTH0_FRONTEND_CLIENT_ID=your-frontend-client-id
export AUTH0_ADMIN_CLIENT_ID=your-admin-console-client-id
export AUTH0_FRONTEND_REDIRECT_URI=https://buyit.local
export AUTH0_ADMIN_REDIRECT_URI=https://admin.local
```

## Step 7: Configure Payments Service

Update `services/payments/src/main/resources/application.yml`:

```yaml
auth0:
  domain: ${AUTH0_DOMAIN:your-tenant.auth0.com}
  audience: ${AUTH0_AUDIENCE:}  # Optional: API audience identifier
```

Or set via environment variables:
```bash
AUTH0_DOMAIN=dev-bm52noc2kc3fce8w.us.auth0.com
AUTH0_AUDIENCE=https://buyit.local/api  # API Identifier from Step 1.5 (required for signed access tokens)
```

**Important**: 
- `AUTH0_AUDIENCE` must match the **API Identifier** you configured in Step 1.5
- The Auth0 API must have **JWE (encryption) disabled** to get signed JWT tokens
- If you don't set `AUTH0_AUDIENCE`, the app will use ID tokens instead (which are always signed JWTs)

## Alternative: Using Auth0 Management API in Action

If your user service is not accessible from Auth0 Actions (e.g., in private network), you can:

1. **Store user metadata in Auth0**:
   - Use Auth0 Management API to store `user_id` and `account_type` in `app_metadata`
   - Update app_metadata when users are created/updated

2. **Read from app_metadata in Action**:

```javascript
exports.onExecutePostLogin = async (event, api) => {
  const namespace = 'https://buyit.local/';
  
  // Read from app_metadata
  const userId = event.user.app_metadata?.user_id;
  const accountType = event.user.app_metadata?.account_type;
  const firstname = event.user.given_name || event.user.app_metadata?.firstname;
  const lastname = event.user.family_name || event.user.app_metadata?.lastname;
  
  if (userId && accountType) {
    api.idToken.setCustomClaim(`${namespace}user_id`, userId);
    api.idToken.setCustomClaim(`${namespace}account_type`, accountType);
    if (firstname) api.idToken.setCustomClaim(`${namespace}firstname`, firstname);
    if (lastname) api.idToken.setCustomClaim(`${namespace}lastname`, lastname);
    
    // Also add to access token
    api.accessToken.setCustomClaim(`${namespace}user_id`, userId);
    api.accessToken.setCustomClaim(`${namespace}account_type`, accountType);
    if (firstname) api.accessToken.setCustomClaim(`${namespace}firstname`, firstname);
    if (lastname) api.accessToken.setCustomClaim(`${namespace}lastname`, lastname);
  }
};
```

3. **Update user service** to sync user data to Auth0 app_metadata when users are created/updated.

## Troubleshooting

### Custom claims not appearing in token

1. **Check Action is deployed and added to flow**
2. **Check Action logs** in Auth0 Dashboard → Actions → [Your Action] → Logs
3. **Verify user service is accessible** from Auth0 (if using fetch approach)
4. **Check claim namespace** matches what your service expects: `https://buyit.local/`

### Token validation failing in payments service

1. **Verify Auth0 domain** matches in application.yml
2. **Check JWKS endpoint** is accessible: `https://YOUR_DOMAIN/.well-known/jwks.json`
3. **Verify issuer** in token matches: `https://YOUR_DOMAIN/`
4. **Check token expiration** - tokens expire after 24 hours by default

### Frontend authentication not working

1. **Check callback URLs** are correctly configured in Auth0
2. **Verify environment variables** are set correctly
3. **Check browser console** for errors
4. **Verify CORS settings** in Auth0 application settings

## Notes

- **Custom claim namespace**: Use a URL format (`https://buyit.local/`) to avoid claim collisions
- **Token expiration**: Access tokens typically expire after 24 hours. The frontend SDK handles token refresh automatically
- **Security**: Always validate JWT tokens in microservices - never trust tokens from clients without verification
- **Performance**: JWKS keys are cached for 24 hours by default to reduce network calls

