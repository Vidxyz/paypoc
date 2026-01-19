package auth

import (
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/lestrrat-go/jwx/v2/jwk"
	"github.com/lestrrat-go/jwx/v2/jwt"
)

const (
	AccountTypeBuyer  = "BUYER"
	AccountTypeSeller = "SELLER"
	AccountTypeAdmin  = "ADMIN"
)

// InternalTokenAuth validates opaque internal API tokens
func InternalTokenAuth(internalToken string) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Missing Authorization header"})
			c.Abort()
			return
		}

		// Check for Bearer token
		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid Authorization header format. Expected: Bearer {token}"})
			c.Abort()
			return
		}

		token := parts[1]
		if token != internalToken {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid internal API token"})
			c.Abort()
			return
		}

		c.Next()
	}
}

// JWTAuth validates JWT tokens and extracts user information
type JWTAuth struct {
	auth0Domain string
	audience    string
	jwksURL     string
}

// NewJWTAuth creates a new JWT authenticator
func NewJWTAuth(auth0Domain, audience string) *JWTAuth {
	return &JWTAuth{
		auth0Domain: auth0Domain,
		audience:    audience,
		jwksURL:     fmt.Sprintf("https://%s/.well-known/jwks.json", auth0Domain),
	}
}

// RequireAccountType creates a middleware that validates JWT and requires specific account types
func (j *JWTAuth) RequireAccountType(allowedTypes ...string) gin.HandlerFunc {
	allowedMap := make(map[string]bool)
	for _, t := range allowedTypes {
		allowedMap[strings.ToUpper(t)] = true
	}

	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Missing Authorization header"})
			c.Abort()
			return
		}

		// Extract Bearer token
		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid Authorization header format. Expected: Bearer {token}"})
			c.Abort()
			return
		}

		tokenStr := parts[1]

		// Fetch JWKS from Auth0
		keySet, err := jwk.Fetch(c.Request.Context(), j.jwksURL)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to fetch JWKS"})
			c.Abort()
			return
		}

		// Parse and validate JWT
		token, err := jwt.Parse(
			[]byte(tokenStr),
			jwt.WithKeySet(keySet),
			jwt.WithValidate(true),
			jwt.WithIssuer(fmt.Sprintf("https://%s/", j.auth0Domain)),
		)
		if err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": fmt.Sprintf("Invalid or expired JWT token: %v", err)})
			c.Abort()
			return
		}

		// Validate audience if configured
		// JWT audience can be either a string or an array of strings
		if j.audience != "" {
			aud, exists := token.Get("aud")
			if !exists {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Token missing audience claim"})
				c.Abort()
				return
			}

			// Handle both string and array cases
			audienceMatch := false
			switch v := aud.(type) {
			case string:
				audienceMatch = v == j.audience
			case []interface{}:
				// Check if configured audience is in the array
				for _, item := range v {
					if str, ok := item.(string); ok && str == j.audience {
						audienceMatch = true
						break
					}
				}
			case []string:
				// Check if configured audience is in the array
				for _, item := range v {
					if item == j.audience {
						audienceMatch = true
						break
					}
				}
			}

			if !audienceMatch {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid token audience"})
				c.Abort()
				return
			}
		}

		// Extract account type from custom claim
		customNamespace := "https://buyit.local/"
		accountTypeClaim, ok := token.Get(customNamespace + "account_type")
		if !ok {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Token missing account_type claim"})
			c.Abort()
			return
		}

		accountType, ok := accountTypeClaim.(string)
		if !ok {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid account_type claim format"})
			c.Abort()
			return
		}

		accountType = strings.ToUpper(strings.TrimSpace(accountType))

		// Check if account type is allowed
		if !allowedMap[accountType] {
			c.JSON(http.StatusForbidden, gin.H{
				"error": fmt.Sprintf("Access denied. Account type '%s' is not allowed. Required: %v", accountType, allowedTypes),
			})
			c.Abort()
			return
		}

		// Extract user identity.
		//
		// IMPORTANT:
		// - For BUYER/ADMIN we use `https://buyit.local/user_id` (UUID) as the principal.
		// - For SELLER we use `https://buyit.local/email` as the principal because our order DB
		//   stores `order_items.seller_id` as the seller email (e.g. seller2@buyit.com).
		var userID string
		if accountType == AccountTypeSeller {
			emailClaim, ok := token.Get(customNamespace + "email")
			if !ok {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Token missing email claim"})
				c.Abort()
				return
			}
			email, ok := emailClaim.(string)
			if !ok {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid email claim format"})
				c.Abort()
				return
			}
			userID = strings.TrimSpace(email)
			if userID == "" {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Empty email claim"})
				c.Abort()
				return
			}
		} else {
			userIDClaim, ok := token.Get(customNamespace + "user_id")
			if !ok {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Token missing user_id claim"})
				c.Abort()
				return
			}

			id, ok := userIDClaim.(string)
			if !ok {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid user_id claim format"})
				c.Abort()
				return
			}
			userID = strings.TrimSpace(id)
			if userID == "" {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Empty user_id claim"})
				c.Abort()
				return
			}
		}

		// Store user info in context for handlers to use
		c.Set("user_id", userID)
		c.Set("account_type", accountType)
		c.Set("jwt_token", tokenStr)

		c.Next()
	}
}

// GetUserID extracts user ID from context (set by JWT middleware)
func GetUserID(c *gin.Context) (string, bool) {
	userID, exists := c.Get("user_id")
	if !exists {
		return "", false
	}
	userIDStr, ok := userID.(string)
	return userIDStr, ok
}

// GetAccountType extracts account type from context (set by JWT middleware)
func GetAccountType(c *gin.Context) (string, bool) {
	accountType, exists := c.Get("account_type")
	if !exists {
		return "", false
	}
	accountTypeStr, ok := accountType.(string)
	return accountTypeStr, ok
}
