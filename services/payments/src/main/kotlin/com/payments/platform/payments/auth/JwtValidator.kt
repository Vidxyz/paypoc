package com.payments.platform.payments.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.payments.platform.payments.models.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.util.*

/**
 * Validates Auth0 JWT tokens and extracts user information from claims.
 * 
 * Expected custom claims (added via Auth0 Actions):
 * - https://buyit.local/user_id: UUID string
 * - https://buyit.local/account_type: BUYER, SELLER, or ADMIN
 * - https://buyit.local/firstname: String (optional)
 * - https://buyit.local/lastname: String (optional)
 */
@Component
class JwtValidator(
    @Value("\${auth0.domain}") private val auth0Domain: String,
    @Value("\${auth0.audience:}") private val audience: String?,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    private val jwkProvider: JwkProvider by lazy {
        val jwksUrl = URL("https://$auth0Domain/.well-known/jwks.json")
        JwkProviderBuilder(jwksUrl)
            .cached(10, 24, java.util.concurrent.TimeUnit.HOURS)
            .rateLimited(false)
            .build()
    }
    
    /**
     * Validates a JWT token and extracts user information from claims.
     * 
     * @param token The JWT token string
     * @return User object extracted from token claims, or null if token is invalid
     */
    fun validateAndExtractUser(token: String): User? {
        return try {
            val decodedJWT = decodeToken(token)
            val user = extractUserFromClaims(decodedJWT)
            logger.debug("Successfully validated JWT token for user: ${user.userId}")
            user
        } catch (e: JWTVerificationException) {
            logger.warn("JWT token validation failed: ${e.message}")
            null
        } catch (e: Exception) {
            logger.error("Error validating JWT token", e)
            null
        }
    }
    
    private fun decodeToken(token: String): DecodedJWT {
        // Decode without verification first to get the key ID
        val unverifiedJWT = JWT.decode(token)
        
        // Get the public key from JWKS
        val jwk = jwkProvider.get(unverifiedJWT.keyId)
        val publicKey = jwk.publicKey as? RSAPublicKey
            ?: throw IllegalStateException("Public key is not an RSA key")
        
        val algorithm = Algorithm.RSA256(publicKey, null)
        
        // Build verifier with issuer and audience checks
        val verifierBuilder = JWT.require(algorithm)
            .withIssuer("https://$auth0Domain/")
        
        // Add audience check if configured
        if (!audience.isNullOrBlank()) {
            verifierBuilder.withAudience(audience)
        }
        
        val verifier: JWTVerifier = verifierBuilder.build()
        
        // Verify the token
        return verifier.verify(token)
    }
    
    private fun extractUserFromClaims(decodedJWT: DecodedJWT): User {
        val claims = decodedJWT.claims
        
        // Extract Auth0 user ID (sub claim)
        val auth0UserId = decodedJWT.subject
            ?: throw IllegalArgumentException("Token missing 'sub' claim")
        
        // Extract custom claims (namespace: https://buyit.local/...)
        val customNamespace = "https://buyit.local/"
        
        val userIdClaim = claims[customNamespace + "user_id"]?.asString()
            ?: throw IllegalArgumentException("Token missing custom claim: ${customNamespace}user_id")
        val userId = try {
            UUID.fromString(userIdClaim)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid user_id format in token: $userIdClaim", e)
        }
        
        val accountTypeClaim = claims[customNamespace + "account_type"]?.asString()
            ?: throw IllegalArgumentException("Token missing custom claim: ${customNamespace}account_type")
        val accountType = User.AccountType.fromString(accountTypeClaim)
            ?: throw IllegalArgumentException("Invalid account_type in token: $accountTypeClaim")
        
        val email = claims["email"]?.asString()
            ?: throw IllegalArgumentException("Token missing 'email' claim")
        
        val firstname = claims[customNamespace + "firstname"]?.asString()
        val lastname = claims[customNamespace + "lastname"]?.asString()
        
        return User(
            userId = userId,
            email = email,
            auth0UserId = auth0UserId,
            accountType = accountType,
            firstname = firstname,
            lastname = lastname,
        )
    }
}

