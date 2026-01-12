package controllers

import models.{SignupRequest, UserResponse, AccountType}
import play.api.Logger
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request}
import services.UserService
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

/**
 * Controller for seller signup.
 * 
 * This endpoint is currently public (no authentication required).
 * 
 * todo-vh: This should be secured with one-time passcode (OTP) for real user creation.
 *          Consider implementing:
 *          - Email/SMS OTP verification before account creation
 *          - Rate limiting to prevent abuse
 *          - CAPTCHA to prevent bot signups
 *          - Optional: Require invitation code or approval workflow
 */
class SellerSignupController @Inject()(
  cc: ControllerComponents,
  userService: UserService
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(getClass)

  /**
   * Create a seller user account.
   * 
   * Currently public (no authentication required).
   * Forces account type to SELLER.
   * 
   * todo-vh: Add OTP verification before allowing signup
   */
  def sellerSignup: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    request.body.asJson match {
      case Some(json) =>
        json.validate[SignupRequest] match {
          case JsSuccess(signupRequest: SignupRequest, _) =>
            // Force account type to SELLER (override any value in request)
            val sellerSignupRequest = signupRequest.copy(accountType = AccountType.SELLER)
            
            logger.info(s"Creating new seller account for email: ${sellerSignupRequest.email}")
            
            userService.signup(sellerSignupRequest).map { user =>
              if (user.accountType != AccountType.SELLER) {
                logger.error(s"Failed to create SELLER account - user was created with type: ${user.accountType}")
                InternalServerError(Json.obj("error" -> "Failed to create seller account - account type mismatch"))
              } else {
                logger.info(s"Successfully created seller account for user: ${user.id} (email: ${user.email})")
                Created(Json.toJson(UserResponse.fromUser(user)))
              }
            }.recover { case e: Exception =>
              logger.error(s"Failed to create seller user: ${e.getMessage}", e)
              InternalServerError(Json.obj("error" -> s"Failed to create seller account: ${e.getMessage}"))
            }
          case JsError(errors) =>
            Future.successful(BadRequest(Json.obj("error" -> "Invalid request", "details" -> JsError.toJson(errors))))
        }
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
    }
  }
}

