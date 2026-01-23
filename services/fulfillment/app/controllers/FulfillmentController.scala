package controllers

import auth.JwtValidator
import models.{AccountType, AddTrackingRequest, ShipmentResponse, UpdateShipmentStatusRequest}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request, Result}
import services.FulfillmentService
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID
import javax.inject.Inject

class FulfillmentController @Inject()(
  cc: ControllerComponents,
  fulfillmentService: FulfillmentService,
  jwtValidator: JwtValidator
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(getClass)

  /**
   * Get shipment by ID - SELLER or ADMIN only
   */
  def getShipment(shipmentId: UUID): Action[AnyContent] = Action.async { implicit request =>
    extractTokenAndValidate { (token, userId, accountType) =>
      requireSellerOrAdmin(accountType)
      
      fulfillmentService.getShipment(shipmentId, userId).map {
        case Some(shipment) =>
          Ok(Json.toJson(ShipmentResponse.fromShipment(shipment)))
        case None =>
          NotFound(Json.obj("error" -> s"Shipment not found: $shipmentId"))
      }.recover {
        case e: SecurityException =>
          Forbidden(Json.obj("error" -> e.getMessage))
        case e: Exception =>
          logger.error("Error getting shipment", e)
          InternalServerError(Json.obj("error" -> "Failed to get shipment"))
      }
    }
  }

  /**
   * List shipments for seller - SELLER or ADMIN only
   */
  def listShipments: Action[AnyContent] = Action.async { implicit request =>
    extractTokenAndValidate { (token, userId, accountType) =>
      requireSellerOrAdmin(accountType)
      
      val limit = request.getQueryString("limit").flatMap(_.toIntOption).getOrElse(50)
      val offset = request.getQueryString("offset").flatMap(_.toIntOption).getOrElse(0)
      
      fulfillmentService.getShipmentsBySeller(userId, limit, offset).map { shipments =>
        Ok(Json.toJson(shipments.map(ShipmentResponse.fromShipment)))
      }.recover {
        case e: Exception =>
          logger.error("Error listing shipments", e)
          InternalServerError(Json.obj("error" -> "Failed to list shipments"))
      }
    }
  }

  /**
   * Update shipment status - SELLER or ADMIN only
   */
  def updateShipmentStatus(shipmentId: UUID): Action[AnyContent] = Action.async { implicit request =>
    extractTokenAndValidate { (token, userId, accountType) =>
      requireSellerOrAdmin(accountType)
      
      request.body.asJson match {
        case Some(json) =>
          json.validate[UpdateShipmentStatusRequest].fold(
            errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request data", "details" -> errors.toString()))),
            updateRequest => {
              fulfillmentService.updateShipmentStatus(shipmentId, userId, updateRequest.status).map { shipment =>
                Ok(Json.toJson(ShipmentResponse.fromShipment(shipment)))
              }.recover {
                case e: SecurityException =>
                  Forbidden(Json.obj("error" -> e.getMessage))
                case e: IllegalArgumentException =>
                  BadRequest(Json.obj("error" -> e.getMessage))
                case e: Exception =>
                  logger.error("Error updating shipment status", e)
                  InternalServerError(Json.obj("error" -> "Failed to update shipment status"))
              }
            }
          )
        case None =>
          Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
      }
    }
  }

  /**
   * Add tracking information - SELLER or ADMIN only
   */
  def addTracking(shipmentId: UUID): Action[AnyContent] = Action.async { implicit request =>
    extractTokenAndValidate { (token, userId, accountType) =>
      requireSellerOrAdmin(accountType)
      
      request.body.asJson match {
        case Some(json) =>
          json.validate[AddTrackingRequest].fold(
            errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request data", "details" -> errors.toString()))),
            addRequest => {
              fulfillmentService.addTracking(shipmentId, userId, addRequest.trackingNumber, addRequest.carrier).map { shipment =>
                Ok(Json.toJson(ShipmentResponse.fromShipment(shipment)))
              }.recover {
                case e: SecurityException =>
                  Forbidden(Json.obj("error" -> e.getMessage))
                case e: IllegalArgumentException =>
                  BadRequest(Json.obj("error" -> e.getMessage))
                case e: Exception =>
                  logger.error("Error adding tracking", e)
                  InternalServerError(Json.obj("error" -> "Failed to add tracking"))
              }
            }
          )
        case None =>
          Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
      }
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

  private def requireSellerOrAdmin(accountType: AccountType): Unit = {
    if (accountType != AccountType.SELLER && accountType != AccountType.ADMIN) {
      throw new SecurityException("Only SELLER or ADMIN users can access this endpoint")
    }
  }
}
