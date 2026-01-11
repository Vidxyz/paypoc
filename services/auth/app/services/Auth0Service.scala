package services

import com.nimbusds.oauth2.sdk.{AuthorizationCodeGrant, TokenRequest}
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.openid.connect.sdk.{OIDCTokenResponse, OIDCTokenResponseParser}
import play.api.Configuration
import play.api.libs.ws.WSClient
import play.api.libs.json.Json
import scala.concurrent.{ExecutionContext, Future}
import java.net.URI
import javax.inject.Inject

case class Auth0Tokens(idToken: String, accessToken: String, refreshToken: String)

class Auth0Service @Inject()(
  ws: WSClient,
  config: Configuration
)(implicit ec: ExecutionContext) {

  private val domain = config.get[String]("auth0.domain")
  private val clientId = config.get[String]("auth0.client.id")
  private val clientSecret = config.get[String]("auth0.client.secret")
  private val redirectUri = config.get[String]("auth0.redirect.uri")
  private val redirectUriPkce = config.get[String]("auth0.redirect.uri.pkce")
  
  private val issuer = s"https://$domain/"
  private val authorizationEndpoint = s"https://$domain/authorize"
  private val tokenEndpoint = s"https://$domain/oauth/token"

  def getAuthorizationUrl(): String = {
    s"$authorizationEndpoint?client_id=$clientId&redirect_uri=$redirectUri&response_type=code&scope=openid+email+profile"
  }

  def getAuthorizationUrlPkce(): String = {
    // TODO: Implement PKCE flow with code verifier/challenge
    getAuthorizationUrl()
  }

  def exchangeCodeForTokens(code: String): Future[Auth0Tokens] = {
    ws.url(tokenEndpoint)
      .withHttpHeaders("Content-Type" -> "application/json")
      .post(Json.obj(
        "grant_type" -> "authorization_code",
        "client_id" -> clientId,
        "client_secret" -> clientSecret,
        "code" -> code,
        "redirect_uri" -> redirectUri
      ))
      .map { response =>
        if (response.status == 200) {
          val json = response.json
          Auth0Tokens(
            idToken = (json \ "id_token").as[String],
            accessToken = (json \ "access_token").as[String],
            refreshToken = (json \ "refresh_token").asOpt[String].getOrElse("")
          )
        } else {
          throw new RuntimeException(s"Token exchange failed: ${response.status} - ${response.body}")
        }
      }
  }

  def logout(returnTo: String): Future[String] = {
    // Return Auth0 logout URL for redirect
    Future.successful(
      s"https://$domain/v2/logout?client_id=$clientId&returnTo=${java.net.URLEncoder.encode(returnTo, "UTF-8")}"
    )
  }
}

