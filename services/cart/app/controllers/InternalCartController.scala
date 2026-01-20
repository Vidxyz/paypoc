package controllers

import play.api.Configuration
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request, Result}
import repositories.CartRepository
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID
import javax.inject.Inject

class InternalCartController @Inject()(
  cc: ControllerComponents,
  cartRepository: CartRepository,
  cartService: services.CartService,
  config: Configuration
)(implicit ec: ExecutionContext) extends AbstractController(cc) {
  
  private val internalApiToken = config.getOptional[String]("cart.internal.api.token").getOrElse("")
  private val logger = Logger(getClass)

  /**
   * Check if a cart exists (by cart ID) - Internal API only
   * Used by inventory service to verify if a cart is still active before releasing reservations
   */
  def checkCartExists(cartId: UUID): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    // Validate internal API token
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
        logger.warn(s"Internal API request attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(providedToken) =>
        if (providedToken != internalApiToken) {
          logger.warn(s"Internal API request attempted with invalid token from ${request.remoteAddress}")
          Future.successful(
            Unauthorized(Json.obj("error" -> "Invalid internal API token"))
          )
        } else {
          // Check Redis first (for active carts)
          cartRepository.getActiveCartByCartId(cartId).flatMap {
            case Some(cart) =>
              // Cart exists in Redis (active) - don't release reservation
              Future.successful(Ok(Json.obj("exists" -> true, "cartId" -> cartId.toString, "status" -> "ACTIVE", "source" -> "redis")))
            case None =>
              // Not in Redis, check PostgreSQL (for persisted carts in checkout/completed)
              cartRepository.getCartByCartId(cartId).map {
                case Some(cartEntity) =>
                  // Cart exists in PostgreSQL - don't release reservation
                  Ok(Json.obj("exists" -> true, "cartId" -> cartId.toString, "status" -> cartEntity.status, "source" -> "postgresql"))
                case None =>
                  // Cart doesn't exist in either Redis or PostgreSQL - safe to release reservation
                  Ok(Json.obj("exists" -> false, "cartId" -> cartId.toString))
              }
          }.recover {
            case e: Exception =>
              logger.error(s"Error checking cart existence for $cartId", e)
              // On error, assume cart exists to be safe (don't release if uncertain)
              Ok(Json.obj("exists" -> true, "cartId" -> cartId.toString, "error" -> "check_failed"))
          }
        }
    }
  }
  
  /**
   * Update cart status (by cart ID) - Internal API only
   * Used by order service to mark carts as COMPLETED or ABANDONED
   */
  def updateCartStatus(cartId: UUID): Action[play.api.libs.json.JsValue] = Action.async(parse.json) { implicit request: Request[play.api.libs.json.JsValue] =>
    // Validate internal API token
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
        logger.warn(s"Internal API request attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(providedToken) =>
        if (providedToken != internalApiToken) {
          logger.warn(s"Internal API request attempted with invalid token from ${request.remoteAddress}")
          Future.successful(
            Unauthorized(Json.obj("error" -> "Invalid internal API token"))
          )
        } else {
          // Parse status from request body
          val statusOpt = (request.body \ "status").asOpt[String]
          
          statusOpt match {
            case None =>
              Future.successful(
                BadRequest(Json.obj("error" -> "Missing 'status' field in request body"))
              )
            case Some(status) =>
              // Validate status
              val validStatuses = Set("CHECKOUT", "COMPLETED", "ABANDONED", "EXPIRED")
              if (!validStatuses.contains(status)) {
                Future.successful(
                  BadRequest(Json.obj("error" -> s"Invalid status: $status. Must be one of: ${validStatuses.mkString(", ")}"))
                )
              } else {
                cartService.updateCartStatus(cartId, status).map {
                  case Some(cartEntity) =>
                    Ok(Json.obj(
                      "cartId" -> cartId.toString,
                      "status" -> cartEntity.status,
                      "updatedAt" -> cartEntity.updatedAt.toString
                    ))
                  case None =>
                    NotFound(Json.obj("error" -> s"Cart not found: $cartId"))
                }.recover {
                  case e: Exception =>
                    logger.error(s"Error updating cart status for $cartId", e)
                    InternalServerError(Json.obj("error" -> s"Failed to update cart status: ${e.getMessage}"))
                }
              }
          }
        }
    }
  }
}
