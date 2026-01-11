package repositories

import models.AccountType
import play.api.Configuration
import play.api.libs.ws.WSClient
import play.api.libs.json.{Json, Reads, JsSuccess, JsError}
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID
import javax.inject.Inject

// Model matching the user service UserResponse
case class User(
  id: UUID,
  email: String,
  firstname: String,
  lastname: String,
  accountType: AccountType
)

object User {
  implicit val accountTypeReads: Reads[AccountType] = Reads[AccountType] {
    case play.api.libs.json.JsString(s) => AccountType.fromString(s)
      .map(JsSuccess(_))
      .getOrElse(JsError("Invalid account type. Must be one of: BUYER, SELLER, ADMIN"))
    case _ => JsError("Expected string for account type")
  }

  implicit val userReads: Reads[User] = Json.reads[User]
}

class UserRepository @Inject()(
  ws: WSClient,
  config: Configuration
)(implicit ec: ExecutionContext) {

  private val userServiceUrl = config.get[String]("user.service.url")

  def findByEmail(email: String): Future[Option[User]] = {
    ws.url(s"$userServiceUrl/users/by-email/$email")
      .get()
      .map { response =>
        if (response.status == 200) {
          response.json.asOpt[User]
        } else {
          None
        }
      }
      .recover { case _ => None }
  }

  def findById(id: UUID): Future[Option[User]] = {
    ws.url(s"$userServiceUrl/users/$id")
      .get()
      .map { response =>
        if (response.status == 200) {
          response.json.asOpt[User]
        } else {
          None
        }
      }
      .recover { case _ => None }
  }
}
