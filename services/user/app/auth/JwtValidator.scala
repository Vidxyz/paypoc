package auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.{JWTVerificationException, TokenExpiredException}
import com.auth0.jwt.interfaces.{DecodedJWT, JWTVerifier}
import com.auth0.jwk.{JwkProvider, JwkProviderBuilder}
import models.AccountType
import play.api.Configuration
import play.api.Logger
import java.net.URL
import java.security.interfaces.RSAPublicKey
import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

/**
 * Validates Auth0 JWT tokens and extracts user information from claims.
 * 
 * Expected custom claims (added via Auth0 Actions):
 * - https://buyit.local/user_id: UUID string
 * - https://buyit.local/account_type: BUYER, SELLER, or ADMIN
 */
@Singleton
class JwtValidator @Inject()(config: Configuration) {
  private val logger = Logger(getClass)
  
  private val auth0Domain = config.get[String]("auth0.domain")
  private val audience = config.getOptional[String]("auth0.audience")
  
  private lazy val jwkProvider: JwkProvider = {
    val jwksUrl = new URL(s"https://$auth0Domain/.well-known/jwks.json")
    new JwkProviderBuilder(jwksUrl)
      .cached(10, 24, java.util.concurrent.TimeUnit.HOURS)
      .rateLimited(false)
      .build()
  }
  
  /**
   * Validates a JWT token and extracts the account type.
   * 
   * @param token The JWT token string
   * @return AccountType if token is valid and contains account_type claim, None otherwise
   *         Returns None if token is expired, invalid, or missing required claims
   */
  def validateAndExtractAccountType(token: String): Option[AccountType] = {
    Try {
      val decodedJWT = decodeToken(token)
      val accountType = extractAccountTypeFromClaims(decodedJWT)
      logger.debug(s"Successfully validated JWT token, account_type: ${accountType.value}")
      accountType
    } match {
      case Success(accountType) => Some(accountType)
      case Failure(e: TokenExpiredException) =>
        logger.warn(s"JWT token expired: ${e.getMessage}")
        None
      case Failure(e: JWTVerificationException) =>
        logger.warn(s"JWT token validation failed: ${e.getMessage}")
        None
      case Failure(e: Exception) =>
        logger.error("Error validating JWT token", e)
        None
    }
  }
  
  /**
   * Validates a JWT token without extracting claims (just checks if it's valid).
   * 
   * @param token The JWT token string
   * @return true if token is valid and not expired, false otherwise
   */
  def validateToken(token: String): Boolean = {
    Try {
      decodeToken(token)
      true
    } match {
      case Success(_) => true
      case Failure(e: TokenExpiredException) =>
        logger.warn(s"JWT token expired: ${e.getMessage}")
        false
      case Failure(e: JWTVerificationException) =>
        logger.warn(s"JWT token validation failed: ${e.getMessage}")
        false
      case Failure(e: Exception) =>
        logger.error("Error validating JWT token", e)
        false
    }
  }
  
  /**
   * Validates a JWT token and extracts the user ID.
   * 
   * @param token The JWT token string
   * @return UUID if token is valid and contains user_id claim, None otherwise
   *         Returns None if token is expired, invalid, or missing required claims
   */
  def validateAndExtractUserId(token: String): Option[java.util.UUID] = {
    Try {
      val decodedJWT = decodeToken(token)
      val userId = extractUserIdFromClaims(decodedJWT)
      logger.debug(s"Successfully extracted user_id from JWT token: $userId")
      userId
    } match {
      case Success(userId) => Some(userId)
      case Failure(e: TokenExpiredException) =>
        logger.warn(s"JWT token expired: ${e.getMessage}")
        None
      case Failure(e: JWTVerificationException) =>
        logger.warn(s"JWT token validation failed: ${e.getMessage}")
        None
      case Failure(e: Exception) =>
        logger.error("Error extracting user_id from JWT token", e)
        None
    }
  }
  
  private def decodeToken(token: String): DecodedJWT = {
    // Decode without verification first to get the key ID
    val unverifiedJWT = JWT.decode(token)
    
    // Get the public key from JWKS
    val jwk = jwkProvider.get(unverifiedJWT.getKeyId)
    val publicKey = jwk.getPublicKey match {
      case rsaKey: RSAPublicKey => rsaKey
      case _ => throw new IllegalStateException("Public key is not an RSA key")
    }
    
    val algorithm = Algorithm.RSA256(publicKey, null)
    
    // Build verifier with issuer and audience checks
    var verifierBuilder = JWT.require(algorithm)
      .withIssuer(s"https://$auth0Domain/")
    
    // Add audience check if configured
    audience.foreach { aud =>
      verifierBuilder = verifierBuilder.withAudience(aud)
    }
    
    val verifier: JWTVerifier = verifierBuilder.build()
    
    // Verify the token
    verifier.verify(token)
  }
  
  private def extractAccountTypeFromClaims(decodedJWT: DecodedJWT): AccountType = {
    val claims = decodedJWT.getClaims
    val customNamespace = "https://buyit.local/"
    
    val accountTypeClaim = Option(claims.get(customNamespace + "account_type"))
      .map(_.asString())
      .getOrElse(throw new IllegalArgumentException(s"Token missing custom claim: ${customNamespace}account_type"))
    
    AccountType.fromString(accountTypeClaim)
      .getOrElse(throw new IllegalArgumentException(s"Invalid account_type in token: $accountTypeClaim"))
  }
  
  private def extractUserIdFromClaims(decodedJWT: DecodedJWT): java.util.UUID = {
    val claims = decodedJWT.getClaims
    val customNamespace = "https://buyit.local/"
    
    val userIdClaim = Option(claims.get(customNamespace + "user_id"))
      .map(_.asString())
      .getOrElse(throw new IllegalArgumentException(s"Token missing custom claim: ${customNamespace}user_id"))
    
    try {
      java.util.UUID.fromString(userIdClaim)
    } catch {
      case e: IllegalArgumentException =>
        throw new IllegalArgumentException(s"Invalid user_id format in token: $userIdClaim", e)
    }
  }
}

