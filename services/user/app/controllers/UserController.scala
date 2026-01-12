package controllers

import auth.JwtValidator
import models.{UserResponse, AccountType}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request}
import services.UserService
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID
import javax.inject.Inject

class UserController @Inject()(
  cc: ControllerComponents,
  userService: UserService,
  jwtValidator: JwtValidator
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(getClass)

  /**
   * Get user by ID - ADMIN only
   */
  def getUser(id: UUID): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
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
        logger.warn(s"Get user attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(jwtToken) =>
        // Validate token and extract account type
        val accountTypeOpt = jwtValidator.validateAndExtractAccountType(jwtToken)
        
        accountTypeOpt match {
          case None =>
            logger.warn(s"Get user attempted with invalid or expired JWT token from ${request.remoteAddress}")
            Future.successful(
              Unauthorized(Json.obj("error" -> "Invalid or expired bearer token"))
            )
          case Some(accountType) if accountType != AccountType.ADMIN =>
            logger.warn(s"Get user attempted by non-ADMIN user (account_type: ${accountType.value}) from ${request.remoteAddress}")
            Future.successful(
              Forbidden(Json.obj("error" -> "Only ADMIN users can access this endpoint"))
            )
          case Some(AccountType.ADMIN) =>
            // Token is valid and user is ADMIN - proceed
            userService.getUser(id).map {
              case Some(user) => Ok(Json.toJson(UserResponse.fromUser(user)))
              case None => NotFound(Json.obj("error" -> s"User not found: $id"))
            }
        }
    }
  }

  /**
   * Get user by email - ADMIN only
   */
  def getUserByEmail(email: String): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
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
        logger.warn(s"Get user by email attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(jwtToken) =>
        // Validate token and extract account type
        val accountTypeOpt = jwtValidator.validateAndExtractAccountType(jwtToken)
        
        accountTypeOpt match {
          case None =>
            logger.warn(s"Get user by email attempted with invalid or expired JWT token from ${request.remoteAddress}")
            Future.successful(
              Unauthorized(Json.obj("error" -> "Invalid or expired bearer token"))
            )
          case Some(accountType) if accountType != AccountType.ADMIN =>
            logger.warn(s"Get user by email attempted by non-ADMIN user (account_type: ${accountType.value}) from ${request.remoteAddress}")
            Future.successful(
              Forbidden(Json.obj("error" -> "Only ADMIN users can access this endpoint"))
            )
          case Some(AccountType.ADMIN) =>
            // Token is valid and user is ADMIN - proceed
            userService.getUserByEmail(email).map {
              case Some(user) => Ok(Json.toJson(UserResponse.fromUser(user)))
              case None => NotFound(Json.obj("error" -> s"User not found with email: $email"))
            }
        }
    }
  }


  /**
   * Update account type - ADMIN only
   */
  def updateAccountType(id: UUID): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
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
        logger.warn(s"Update account type attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(jwtToken) =>
        // Validate token and extract account type
        val accountTypeOpt = jwtValidator.validateAndExtractAccountType(jwtToken)
        
        accountTypeOpt match {
          case None =>
            logger.warn(s"Update account type attempted with invalid or expired JWT token from ${request.remoteAddress}")
            Future.successful(
              Unauthorized(Json.obj("error" -> "Invalid or expired bearer token"))
            )
          case Some(accountType) if accountType != AccountType.ADMIN =>
            logger.warn(s"Update account type attempted by non-ADMIN user (account_type: ${accountType.value}) from ${request.remoteAddress}")
            Future.successful(
              Forbidden(Json.obj("error" -> "Only ADMIN users can access this endpoint"))
            )
          case Some(AccountType.ADMIN) =>
            // Token is valid and user is ADMIN - proceed
            request.body.asJson match {
              case Some(json) =>
                json.\("account_type").asOpt[String] match {
                  case Some(accountTypeStr) =>
                    models.AccountType.fromString(accountTypeStr) match {
                      case Some(newAccountType) =>
                        userService.updateAccountType(id, newAccountType).map {
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
    }
  }

  /**
   * Get current user - requires authentication (any account type)
   */
  def getCurrentUser: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
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
        logger.warn(s"Get current user attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(jwtToken) =>
        // Validate token (any account type is allowed)
        if (!jwtValidator.validateToken(jwtToken)) {
          logger.warn(s"Get current user attempted with invalid or expired JWT token from ${request.remoteAddress}")
          Future.successful(
            Unauthorized(Json.obj("error" -> "Invalid or expired bearer token"))
          )
        } else {
          // Extract account type to get user ID from token
          val accountTypeOpt = jwtValidator.validateAndExtractAccountType(jwtToken)
          accountTypeOpt match {
            case None =>
              Future.successful(
                Unauthorized(Json.obj("error" -> "Invalid or expired bearer token"))
              )
            case Some(_) =>
              // TODO: Extract user_id from token and fetch user from database
              // For now, return not implemented
              Future.successful(NotImplemented(Json.obj("error" -> "Not fully implemented: user lookup from token required")))
          }
        }
    }
  }
}
