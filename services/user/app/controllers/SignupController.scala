package controllers

import models.{SignupRequest, UserResponse}
import models._
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request}
import services.UserService
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

class SignupController @Inject()(
  cc: ControllerComponents,
  userService: UserService
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def signup: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    request.body.asJson match {
      case Some(json) =>
        json.validate[SignupRequest] match {
          case JsSuccess(signupRequest, _) =>
            userService.signup(signupRequest).map { user =>
              Created(Json.toJson(UserResponse.fromUser(user)))
            }.recover { case e: Exception =>
              InternalServerError(Json.obj("error" -> s"Failed to create user: ${e.getMessage}"))
            }
          case JsError(errors) =>
            Future.successful(BadRequest(Json.obj("error" -> "Invalid request", "details" -> JsError.toJson(errors))))
        }
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
    }
  }
}
