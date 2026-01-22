package controllers

import models.CarrierWebhookRequest
import play.api.Configuration
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import services.FulfillmentService
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

class CarrierWebhookController @Inject()(
  cc: ControllerComponents,
  fulfillmentService: FulfillmentService,
  config: Configuration
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(getClass)
  private val internalApiToken = config.getOptional[String]("fulfillment.internal.api.token")

  /**
   * Generic carrier webhook endpoint
   * POST /api/webhooks/carriers/:carrier
   */
  def handleCarrierWebhook(carrier: String): Action[AnyContent] = Action.async { implicit request =>
    // Validate internal API token for webhook security
    val authHeader = request.headers.get("Authorization")
    val token = authHeader.flatMap { header =>
      if (header.startsWith("Bearer ")) {
        Some(header.substring(7))
      } else {
        None
      }
    }

    val isValidToken = token.exists { t =>
      internalApiToken.exists(_ == t)
    }

    if (!isValidToken) {
      logger.warn(s"Unauthorized webhook attempt from ${request.remoteAddress}")
      Future.successful(Unauthorized(Json.obj("error" -> "Invalid or missing authorization token")))
    } else {
      request.body.asJson match {
        case Some(json) =>
          json.validate[CarrierWebhookRequest].fold(
            errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request data", "details" -> errors.toString()))),
            webhookRequest => {
              fulfillmentService.processCarrierWebhook(
                carrier = carrier,
                trackingNumber = webhookRequest.trackingNumber,
                carrierStatus = webhookRequest.status,
                metadata = webhookRequest.metadata
              ).map {
                case Some(shipment) =>
                  Ok(Json.obj("status" -> "processed", "shipment_id" -> shipment.id.toString))
                case None =>
                  Accepted(Json.obj("status" -> "received", "message" -> "Webhook received but shipment not found"))
              }.recover {
                case e: Exception =>
                  logger.error("Error processing carrier webhook", e)
                  InternalServerError(Json.obj("error" -> "Failed to process webhook"))
              }
            }
          )
        case None =>
          Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
      }
    }
  }

  /**
   * UPS webhook endpoint
   * POST /api/webhooks/ups
   */
  def handleUpsWebhook: Action[AnyContent] = handleCarrierWebhook("UPS")

  /**
   * FedEx webhook endpoint
   * POST /api/webhooks/fedex
   */
  def handleFedExWebhook: Action[AnyContent] = handleCarrierWebhook("FEDEX")

  /**
   * DHL webhook endpoint
   * POST /api/webhooks/dhl
   */
  def handleDhlWebhook: Action[AnyContent] = handleCarrierWebhook("DHL")
}
