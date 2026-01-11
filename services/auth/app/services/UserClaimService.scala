package services

import models.{AccountType, EnhancedUserClaims}
import play.api.Configuration
import play.api.libs.ws.WSClient
import play.api.libs.json.Json
import repositories.UserRepository
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID
import javax.inject.Inject

class UserClaimService @Inject()(
  ws: WSClient,
  config: Configuration,
  userRepository: UserRepository
)(implicit ec: ExecutionContext) {

  private val userServiceUrl = config.get[String]("user.service.url")

  def mergeClaims(idToken: String, email: String, auth0UserId: String): Future[EnhancedUserClaims] = {
    userRepository.findByEmail(email).map {
      case Some(user) =>
        // User.accountType is already AccountType (strict type from JSON deserialization)
        EnhancedUserClaims(
          sub = auth0UserId,
          email = email,
          userId = user.id,
          accountType = user.accountType,
          firstname = user.firstname,
          lastname = user.lastname
        )
      case None =>
        throw new RuntimeException(s"User not found with email: $email")
    }
  }

  def refreshClaims(userId: UUID, email: String, auth0UserId: String): Future[EnhancedUserClaims] = {
    userRepository.findById(userId).map {
      case Some(user) =>
        // User.accountType is already AccountType (strict type from JSON deserialization)
        EnhancedUserClaims(
          sub = auth0UserId,
          email = email,
          userId = userId,
          accountType = user.accountType,
          firstname = user.firstname,
          lastname = user.lastname
        )
      case None =>
        throw new RuntimeException(s"User not found with ID: $userId")
    }
  }
}
