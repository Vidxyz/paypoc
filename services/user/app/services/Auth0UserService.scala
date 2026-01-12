package services

import play.api.Configuration
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import scala.concurrent.{ExecutionContext, Future}
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import javax.inject.Inject

case class CachedToken(token: String, expiresAt: Instant)

class Auth0UserService @Inject()(
  ws: WSClient,
  config: Configuration
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)
  private val domain = config.get[String]("auth0.domain")
  private val m2mClientId = config.get[String]("auth0.m2m.client.id")
  private val m2mClientSecret = config.get[String]("auth0.m2m.client.secret")
  
  private val managementApiUrl = s"https://$domain/api/v2"
  private val tokenEndpoint = s"https://$domain/oauth/token"
  
  // Token cache with expiration (refresh 1 hour before expiry to be safe)
  private val tokenCache = new AtomicReference[Option[CachedToken]](None)
  private val tokenRefreshBufferSeconds = 3600L // 1 hour before expiry

  /**
   * Fetches a Management API token using M2M client credentials flow.
   * Implements caching to avoid fetching a new token on every request.
   */
  private def getManagementApiToken: Future[String] = {
    val cached = tokenCache.get()
    
    // Check if cached token is still valid (with buffer for refresh)
    val now = Instant.now()
    cached match {
      case Some(CachedToken(token, expiresAt)) if expiresAt.isAfter(now.plusSeconds(tokenRefreshBufferSeconds)) =>
        logger.debug("Using cached Auth0 Management API token")
        Future.successful(token)
      case _ =>
        // Token expired or doesn't exist, fetch new one
        logger.info("Fetching new Auth0 Management API token")
        fetchNewToken()
    }
  }

  /**
   * Fetches a new Management API token from Auth0 using Play's WSClient.
   */
  private def fetchNewToken(): Future[String] = {
    val body = Json.obj(
      "client_id" -> m2mClientId,
      "client_secret" -> m2mClientSecret,
      "audience" -> s"https://$domain/api/v2/",
      "grant_type" -> "client_credentials"
    )

    ws.url(tokenEndpoint)
      .withHttpHeaders("Content-Type" -> "application/json")
      .post(body)
      .map { response =>
        if (response.status == 200) {
          val json = response.json
          val accessToken = (json \ "access_token").as[String]
          val expiresIn = (json \ "expires_in").asOpt[Int].getOrElse(86400) // Default to 24 hours
          
          // Cache token with expiration
          val expiresAt = Instant.now().plusSeconds(expiresIn)
          tokenCache.set(Some(CachedToken(accessToken, expiresAt)))
          
          logger.info(s"Successfully fetched Auth0 Management API token (expires in ${expiresIn}s)")
          accessToken
        } else {
          val errorMsg = s"Failed to fetch Auth0 Management API token: ${response.status} - ${response.body}"
          logger.error(errorMsg)
          throw new RuntimeException(errorMsg)
        }
      }
      .recover { case e: Exception =>
        logger.error(s"Error fetching Auth0 Management API token: ${e.getMessage}", e)
        throw new RuntimeException(s"Failed to fetch Auth0 Management API token. Please check M2M credentials and network connectivity. Error: ${e.getMessage}", e)
      }
  }

  def createUser(email: String, password: String, firstname: String, lastname: String): Future[String] = {
    val url = s"$managementApiUrl/users"
    
    val body = Json.obj(
      "email" -> email,
      "password" -> password,
      "email_verified" -> true,
      "given_name" -> firstname,
      "family_name" -> lastname,
      "connection" -> "Username-Password-Authentication" // Default Auth0 database connection
    )

    logger.info(s"Creating user in Auth0: $email at $url")
    
    // Get token (with caching) and then create user
    getManagementApiToken.flatMap { token =>
      ws.url(url)
        .withHttpHeaders(
          "Authorization" -> s"Bearer $token",
          "Content-Type" -> "application/json"
        )
        .post(body)
        .map { response =>
          val statusCode = response.status
          if (statusCode == 200 || statusCode == 201) {
            val json = response.json
            val userId = (json \ "user_id").as[String]
            logger.info(s"Successfully created user in Auth0: $userId")
            userId
          } else if (statusCode == 401) {
            // Token might have expired, clear cache and retry once
            logger.warn("Received 401, clearing token cache and retrying")
            tokenCache.set(None)
            throw new RuntimeException("Auth0 token expired, retry required")
          } else {
            val errorMsg = s"Failed to create user in Auth0: $statusCode - ${response.body}"
            logger.error(errorMsg)
            throw new RuntimeException(errorMsg)
          }
        }
        .recover { case e: Exception =>
          logger.error(s"Error creating user in Auth0: ${e.getMessage}", e)
          throw new RuntimeException(s"Failed to create user in Auth0. Error: ${e.getMessage}", e)
        }
    }
  }

  /**
   * Updates Auth0 user's app_metadata with custom user information.
   * This metadata is then used by Auth0 Actions to add custom claims to tokens.
   * 
   * Note: Using "app_user_id" instead of "user_id" to avoid Auth0 property name conflicts.
   * 
   * @param auth0UserId The Auth0 user ID (sub claim)
   * @param userId The application's user UUID
   * @param email User's email address
   * @param accountType The user's account type (BUYER, SELLER, ADMIN)
   * @param firstname User's first name
   * @param lastname User's last name
   */
  def updateUserMetadata(
    auth0UserId: String,
    userId: java.util.UUID,
    email: String,
    accountType: String,
    firstname: String,
    lastname: String
  ): Future[Unit] = {
    val url = s"$managementApiUrl/users/${java.net.URLEncoder.encode(auth0UserId, "UTF-8")}"
    
    // Use "app_user_id" instead of "user_id" to avoid Auth0 property name conflicts
    // Auth0 may reserve certain property names like "user_id" and "email"
    // Store email and auth0_user_id for consistency and to ensure they're available in tokens
    val appMetadata = Json.obj(
      "app_user_id" -> userId.toString,
      "auth0_user_id" -> auth0UserId,
      "app_email" -> email, // Use "app_email" instead of "email" to avoid Auth0 property conflicts
      "account_type" -> accountType,
      "firstname" -> firstname,
      "lastname" -> lastname
    )
    
    val body = Json.obj(
      "app_metadata" -> appMetadata
    )

    logger.info(s"Updating Auth0 user metadata for: $auth0UserId (user_id: $userId)")
    
    getManagementApiToken.flatMap { token =>
      ws.url(url)
        .withHttpHeaders(
          "Authorization" -> s"Bearer $token",
          "Content-Type" -> "application/json"
        )
        .patch(body) // Use PATCH to update user
        .map { response =>
          val statusCode = response.status
          if (statusCode == 200) {
            logger.info(s"Successfully updated Auth0 user metadata for: $auth0UserId")
          } else if (statusCode == 401) {
            // Token might have expired, clear cache and retry once
            logger.warn("Received 401 when updating metadata, clearing token cache")
            tokenCache.set(None)
            throw new RuntimeException("Auth0 token expired, retry required")
          } else {
            val errorMsg = s"Failed to update Auth0 user metadata: $statusCode - ${response.body}"
            logger.error(errorMsg)
            throw new RuntimeException(errorMsg)
          }
        }
        .recover { case e: Exception =>
          logger.error(s"Error updating Auth0 user metadata: ${e.getMessage}", e)
          throw new RuntimeException(s"Failed to update Auth0 user metadata. Error: ${e.getMessage}", e)
        }
    }
  }
}
