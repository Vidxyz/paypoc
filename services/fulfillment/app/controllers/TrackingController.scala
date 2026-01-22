package controllers

import auth.JwtValidator
import clients.OrderServiceClient
import models.{AccountType, ShipmentResponse}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request, Result}
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID
import javax.inject.Inject

class TrackingController @Inject()(
  cc: ControllerComponents,
  orderServiceClient: OrderServiceClient,
  jwtValidator: JwtValidator
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(getClass)

  /**
   * Get tracking information for an order - BUYER or ADMIN only
   * Returns all shipments for the order
   */
  def getOrderTracking(orderId: UUID): Action[AnyContent] = Action.async { implicit request =>
    extractTokenAndValidate { (token, userId, accountType) =>
      requireBuyerOrAdmin(accountType)
      
      // Get all shipments for the order
      orderServiceClient.getShipmentsByOrder(orderId).map { shipments =>
        Ok(Json.toJson(shipments.map(ShipmentResponse.fromShipment)))
      }.recover {
        case e: Exception =>
          logger.error("Error getting order tracking", e)
          InternalServerError(Json.obj("error" -> "Failed to get tracking information"))
      }
    }
  }

  /**
   * Get tracking information for a shipment by tracking number - BUYER or ADMIN only
   * Note: This requires Order Service to support lookup by tracking number
   */
  def getTrackingByNumber(trackingNumber: String): Action[AnyContent] = Action.async { implicit request =>
    extractTokenAndValidate { (token, userId, accountType) =>
      requireBuyerOrAdmin(accountType)
      
      // TODO: Implement lookup by tracking number when Order Service supports it
      Future.successful(
        NotImplemented(Json.obj("error" -> "Tracking lookup by number not yet implemented"))
      )
    }
  }

  private def extractTokenAndValidate[T](f: (String, String, AccountType) => Future[Result])(implicit request: Request[AnyContent]): Future[Result] = {
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
        logger.warn(s"Request attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(jwtToken) =>
        val accountTypeOpt = jwtValidator.validateAndExtractAccountType(jwtToken)
        val userIdOpt = jwtValidator.validateAndExtractUserId(jwtToken)
        
        (accountTypeOpt, userIdOpt) match {
          case (None, _) | (_, None) =>
            logger.warn(s"Request attempted with invalid or expired JWT token from ${request.remoteAddress}")
            Future.successful(
              Unauthorized(Json.obj("error" -> "Invalid or expired bearer token"))
            )
          case (Some(accountType), Some(userId)) =>
            f(jwtToken, userId, accountType)
        }
    }
  }

  private def requireBuyerOrAdmin(accountType: AccountType): Unit = {
    if (accountType != AccountType.BUYER && accountType != AccountType.ADMIN) {
      throw new SecurityException("Only BUYER or ADMIN users can access this endpoint")
    }
  }
}
