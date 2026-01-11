package controllers

import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request}
import services.{TokenService, UserClaimService, Auth0Service}
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

class AuthController @Inject()(
  cc: ControllerComponents,
  auth0Service: Auth0Service,
  userClaimService: UserClaimService,
  tokenService: TokenService
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def login: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    Future.successful(Redirect(auth0Service.getAuthorizationUrl()))
  }

  def callback: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    request.getQueryString("code") match {
      case Some(code) =>
        for {
          tokens <- auth0Service.exchangeCodeForTokens(code)
          // Extract email and Auth0 user ID from ID token (TODO: implement proper JWT parsing)
          email <- Future.successful("") // TODO: Extract from ID token
          auth0UserId <- Future.successful("") // TODO: Extract from ID token
          claims <- userClaimService.mergeClaims(tokens.idToken, email, auth0UserId)
          jwt <- tokenService.createEnhancedJWT(claims)
          refreshToken <- tokenService.createRefreshToken(claims.userId, tokens.refreshToken)
        } yield {
          Ok(Json.obj(
            "access_token" -> jwt,
            "refresh_token" -> refreshToken,
            "token_type" -> "Bearer",
            "expires_in" -> 3600
          ))
        }
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Missing authorization code")))
    }
  }

  def loginPkce: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    Future.successful(Redirect(auth0Service.getAuthorizationUrlPkce()))
  }

  def callbackPkce: Action[AnyContent] = callback // Same logic as standard callback

  def refresh: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    request.body.asJson.flatMap(_.\("refresh_token").asOpt[String]) match {
      case Some(refreshToken) =>
        for {
          existingClaims <- tokenService.validateRefreshToken(refreshToken)
          updatedClaims <- userClaimService.refreshClaims(existingClaims.userId, existingClaims.email, existingClaims.sub)
          jwt <- tokenService.createEnhancedJWT(updatedClaims)
        } yield {
          Ok(Json.obj(
            "access_token" -> jwt,
            "token_type" -> "Bearer",
            "expires_in" -> 3600
          ))
        }
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Missing refresh_token")))
    }
  }

  def logout: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    request.body.asJson.flatMap(_.\("refresh_token").asOpt[String]) match {
      case Some(refreshToken) =>
        for {
          _ <- tokenService.invalidateRefreshToken(refreshToken)
          logoutUrl <- auth0Service.logout("http://localhost:9001") // TODO: Make returnTo configurable
        } yield {
          Ok(Json.obj(
            "message" -> "Logged out successfully",
            "logout_url" -> logoutUrl
          ))
        }
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Missing refresh_token")))
    }
  }

  def getCurrentUser: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    request.headers.get("Authorization") match {
      case Some(authHeader) if authHeader.startsWith("Bearer ") =>
        val token = authHeader.substring(7)
        tokenService.validateJWT(token) match {
          case Some(claims) =>
            Future.successful(Ok(Json.obj(
              "user_id" -> claims.userId.toString,
              "email" -> claims.email,
              "firstname" -> claims.firstname,
              "lastname" -> claims.lastname,
              "account_type" -> claims.accountType.value
            )))
          case None =>
            Future.successful(Unauthorized(Json.obj("error" -> "Invalid or expired token")))
        }
      case _ =>
        Future.successful(Unauthorized(Json.obj("error" -> "Missing Authorization header")))
    }
  }
}
