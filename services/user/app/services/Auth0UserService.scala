package services

import play.api.Configuration
import play.api.libs.ws.WSClient
import play.api.libs.json.{JsObject, Json}
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

class Auth0UserService @Inject()(
  ws: WSClient,
  config: Configuration
)(implicit ec: ExecutionContext) {

  private val domain = config.get[String]("auth0.domain")
  private val managementApiToken = config.getOptional[String]("auth0.management.api.token").getOrElse("")
  
  private val managementApiUrl = s"https://$domain/api/v2"

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

    ws.url(url)
      .withHttpHeaders(
        "Authorization" -> s"Bearer $managementApiToken",
        "Content-Type" -> "application/json"
      )
      .post(body)
      .map { response =>
        if (response.status == 200 || response.status == 201) {
          (response.json \ "user_id").as[String]
        } else {
          throw new RuntimeException(s"Failed to create user in Auth0: ${response.status} - ${response.body}")
        }
      }
  }
}

