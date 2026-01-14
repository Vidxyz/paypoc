package com.payments.platform.inventory.auth

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
import java.util.*

data class User(
    val userId: UUID,
    val email: String,
    val auth0UserId: String,
    val accountType: AccountType
) {
    enum class AccountType {
        BUYER, SELLER, ADMIN;
        
        companion object {
            fun fromString(value: String): AccountType? {
                return values().find { it.name == value.uppercase() }
            }
        }
    }
}

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
    
    fun validateAndExtractUser(token: String): User? {
        return try {
            val decodedJWT = decodeToken(token)
            val user = extractUserFromClaims(decodedJWT)
            logger.debug("Successfully validated JWT token for user: ${user.userId}")
            user
        } catch (e: TokenExpiredException) {
            logger.warn("JWT token expired: ${e.message}")
            null
        } catch (e: JWTVerificationException) {
            logger.warn("JWT token validation failed: ${e.message}")
            null
        } catch (e: Exception) {
            logger.error("Error validating JWT token: ${e.message}", e)
            null
        }
    }
    
    private fun decodeToken(token: String): DecodedJWT {
        val unverifiedJWT = JWT.decode(token)
        val jwk = jwkProvider.get(unverifiedJWT.keyId)
        val publicKey = jwk.publicKey as? RSAPublicKey
            ?: throw IllegalStateException("Public key is not an RSA key")
        
        val algorithm = Algorithm.RSA256(publicKey, null)
        val verifierBuilder = JWT.require(algorithm)
            .withIssuer("https://$auth0Domain/")
        
        if (!audience.isNullOrBlank()) {
            verifierBuilder.withAudience(audience)
        }
        
        val verifier: JWTVerifier = verifierBuilder.build()
        return verifier.verify(token)
    }
    
    private fun extractUserFromClaims(decodedJWT: DecodedJWT): User {
        val claims = decodedJWT.claims
        val customNamespace = "https://buyit.local/"
        
        val userIdClaim = claims[customNamespace + "user_id"]?.asString()
            ?: throw IllegalArgumentException("Token missing custom claim: ${customNamespace}user_id")
        val userId = UUID.fromString(userIdClaim)
        
        val accountTypeClaim = claims[customNamespace + "account_type"]?.asString()
            ?: throw IllegalArgumentException("Token missing custom claim: ${customNamespace}account_type")
        val accountType = User.AccountType.fromString(accountTypeClaim)
            ?: throw IllegalArgumentException("Invalid account_type in token: $accountTypeClaim")
        
        val email = claims[customNamespace + "email"]?.asString()
            ?: claims["email"]?.asString()
            ?: throw IllegalArgumentException("Token missing 'email' claim")
        
        return User(
            userId = userId,
            email = email,
            auth0UserId = decodedJWT.subject,
            accountType = accountType
        )
    }
}

