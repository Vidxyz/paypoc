package controllers

import models.UserResponse
import models._
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request}
import services.UserService
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID
import javax.inject.Inject

class UserController @Inject()(
  cc: ControllerComponents,
  userService: UserService
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def getUser(id: UUID): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    userService.getUser(id).map {
      case Some(user) => Ok(Json.toJson(UserResponse.fromUser(user)))
      case None => NotFound(Json.obj("error" -> s"User not found: $id"))
    }
  }

  def getUserByEmail(email: String): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    userService.getUserByEmail(email).map {
      case Some(user) => Ok(Json.toJson(UserResponse.fromUser(user)))
      case None => NotFound(Json.obj("error" -> s"User not found with email: $email"))
    }
  }

  def getUserByAuth0Id(auth0Id: String): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    userService.getUserByAuth0Id(auth0Id).map {
      case Some(user) => Ok(Json.toJson(UserResponse.fromUser(user)))
      case None => NotFound(Json.obj("error" -> s"User not found with Auth0 ID: $auth0Id"))
    }
  }

  def updateAccountType(id: UUID): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    request.body.asJson match {
      case Some(json) =>
        json.\("account_type").asOpt[String] match {
          case Some(accountTypeStr) =>
            models.AccountType.fromString(accountTypeStr) match {
              case Some(accountType) =>
                userService.updateAccountType(id, accountType).map {
                  case Some(user) => Ok(Json.toJson(UserResponse.fromUser(user)))
                  case None => NotFound(Json.obj("error" -> s"User not found: $id"))
                }
              case None =>
                Future.successful(BadRequest(Json.obj(
                  "error" -> s"Invalid account_type: '$accountTypeStr'. Must be one of: BUYER, SELLER, ADMIN"
                )))
            }
          case None =>
            Future.successful(BadRequest(Json.obj("error" -> "Missing required field: account_type")))
        }
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
    }
  }

  def getCurrentUser: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    // TODO: Extract user ID from JWT token
    Future.successful(Unauthorized(Json.obj("error" -> "Not implemented: JWT validation required")))
  }
}
