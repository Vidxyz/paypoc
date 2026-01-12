package controllers

import auth.JwtValidator
import models.{SignupRequest, UserResponse, AccountType}
import play.api.Logger
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request}
import services.UserService
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

/**
 * Controller for admin-only signup.
 * 
 * This endpoint requires:
 * - Bearer token authentication (JWT)
 * - Token must be valid and contain account_type claim with value ADMIN
 */
class AdminSignupController @Inject()(
  cc: ControllerComponents,
  userService: UserService,
  jwtValidator: JwtValidator
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(getClass)

  /**
   * Create an admin user account.
   * 
   * Protected by:
   * - Bearer token authentication (JWT token in Authorization header)
   * - Token must be valid and account_type claim must be ADMIN
   */
  def adminSignup: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    // Extract bearer token from Authorization header
    val authHeader = request.headers.get("Authorization")
    val token = authHeader.flatMap { header =>
      if (header.startsWith("Bearer ")) {
        Some(header.substring(7))
      } else {
        None
      }
    }

    token match {
      case None =>
        logger.warn(s"Admin signup attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(tokenValue) =>
        // Validate token and extract account type
        val accountTypeOpt = jwtValidator.validateAndExtractAccountType(tokenValue)
        
        accountTypeOpt match {
          case None =>
            logger.warn(s"Admin signup attempted with invalid JWT token from ${request.remoteAddress}")
            Future.successful(
              Unauthorized(Json.obj("error" -> "Invalid or expired bearer token"))
            )
          case Some(accountType) if accountType != AccountType.ADMIN =>
            logger.warn(s"Admin signup attempted by non-ADMIN user (account_type: ${accountType.value}) from ${request.remoteAddress}")
            Future.successful(
              Forbidden(Json.obj("error" -> "Only ADMIN users can create admin accounts"))
            )
          case Some(AccountType.ADMIN) =>
            // Token is valid and user is ADMIN - proceed with signup
            request.body.asJson match {
              case Some(json) =>
                json.validate[SignupRequest] match {
                  case JsSuccess(signupRequest: SignupRequest, _) =>
                    // Force account type to ADMIN (override any value in request)
                    val adminSignupRequest = signupRequest.copy(accountType = AccountType.ADMIN)
                    
                    logger.info(s"Admin user creating new admin account for email: ${adminSignupRequest.email}")
                    
                    userService.signup(adminSignupRequest).map { user =>
                      if (user.accountType != AccountType.ADMIN) {
                        logger.error(s"Failed to create ADMIN account - user was created with type: ${user.accountType}")
                        InternalServerError(Json.obj("error" -> "Failed to create admin account - account type mismatch"))
                      } else {
                        logger.info(s"Successfully created admin account for user: ${user.id} by admin")
                        Created(Json.toJson(UserResponse.fromUser(user)))
                      }
                    }.recover { case e: Exception =>
                      logger.error(s"Failed to create admin user: ${e.getMessage}", e)
                      InternalServerError(Json.obj("error" -> s"Failed to create admin account: ${e.getMessage}"))
                    }
                  case JsError(errors) =>
                    Future.successful(BadRequest(Json.obj("error" -> "Invalid request", "details" -> JsError.toJson(errors))))
                }
              case None =>
                Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
            }
        }
    }
  }
}

