package com.payments.platform.ledger.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URL
import java.security.interfaces.RSAPublicKey

/**
 * Validates Auth0 JWT tokens and extracts account type for authorization.
 * 
 * Expected custom claims (added via Auth0 Actions):
 * - https://buyit.local/account_type: BUYER, SELLER, or ADMIN
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
     * Validates a JWT token and extracts the account type.
     * 
     * @param token The JWT token string
     * @return AccountType string if token is valid and contains account_type claim, null otherwise
     */
    fun validateAndExtractAccountType(token: String): String? {
        return try {
            val decodedJWT = decodeToken(token)
            val accountType = extractAccountTypeFromClaims(decodedJWT)
            logger.debug("Successfully validated JWT token, account_type: $accountType")
            accountType
        } catch (e: TokenExpiredException) {
            logger.warn("JWT token expired: ${e.message}")
            null
        } catch (e: JWTVerificationException) {
            logger.warn("JWT token validation failed: ${e.message}")
            null
        } catch (e: Exception) {
            logger.error("Error validating JWT token", e)
            null
        }
    }
    
    /**
     * Validates a JWT token without extracting claims (just checks if it's valid and not expired).
     * 
     * @param token The JWT token string
     * @return true if token is valid and not expired, false otherwise
     */
    fun validateToken(token: String): Boolean {
        return try {
            decodeToken(token)
            true
        } catch (e: TokenExpiredException) {
            logger.warn("JWT token expired: ${e.message}")
            false
        } catch (e: JWTVerificationException) {
            logger.warn("JWT token validation failed: ${e.message}")
            false
        } catch (e: Exception) {
            logger.error("Error validating JWT token", e)
            false
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
        
        // Verify the token (this also checks expiration)
        return verifier.verify(token)
    }
    
    private fun extractAccountTypeFromClaims(decodedJWT: DecodedJWT): String {
        val claims = decodedJWT.claims
        val customNamespace = "https://buyit.local/"
        
        val accountTypeClaim = claims[customNamespace + "account_type"]?.asString()
            ?: throw IllegalArgumentException("Token missing custom claim: ${customNamespace}account_type")
        
        // Validate account type
        val validAccountTypes = listOf("BUYER", "SELLER", "ADMIN")
        if (!validAccountTypes.contains(accountTypeClaim.uppercase())) {
            throw IllegalArgumentException("Invalid account_type in token: $accountTypeClaim")
        }
        
        return accountTypeClaim.uppercase()
    }
}

