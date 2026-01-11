package services

import models.{EnhancedUserClaims, RefreshToken}
import play.api.Configuration
import play.api.libs.json.{Json, JsObject}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtJson}
import repositories.RefreshTokenRepository
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class TokenService @Inject()(
  config: Configuration,
  refreshTokenRepository: RefreshTokenRepository
)(implicit ec: ExecutionContext) {

  private val jwtSecret = config.get[String]("jwt.secret.key")
  private val jwtExpirationSeconds = config.get[Int]("jwt.expiration.seconds")
  private val refreshTokenExpirationSeconds = config.get[Int]("refresh.token.expiration.seconds")

  def createEnhancedJWT(claims: EnhancedUserClaims): Future[String] = {
    Future {
      val customClaims = Json.obj(
        "email" -> claims.email,
        "user_id" -> claims.userId.toString,
        "account_type" -> claims.accountType.value,
        "firstname" -> claims.firstname,
        "lastname" -> claims.lastname
      )
      val claim = JwtClaim(
        issuer = Some("auth-service"),
        subject = Some(claims.sub),
        expiration = Some(Instant.now().plusSeconds(jwtExpirationSeconds).getEpochSecond),
        issuedAt = Some(Instant.now().getEpochSecond),
        content = customClaims.toString()
      )
      JwtJson.encode(claim, jwtSecret, JwtAlgorithm.HS256)
    }
  }

  def validateJWT(token: String): Option[EnhancedUserClaims] = {
    // TODO: Decode and validate JWT, extract claims
    None
  }

  def createRefreshToken(userId: UUID, auth0RefreshToken: String): Future[String] = {
    // TODO: Hash refresh token, store in database, return token hash
    Future.successful("refresh-token-hash")
  }

  def validateRefreshToken(refreshToken: String): Future[EnhancedUserClaims] = {
    // TODO: Validate refresh token from database, return claims
    Future.failed(new NotImplementedError("Refresh token validation not yet implemented"))
  }

  def invalidateRefreshToken(refreshToken: String): Future[Unit] = {
    // TODO: Delete refresh token from database
    Future.unit
  }
}
