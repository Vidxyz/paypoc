package clients

import java.time.Instant
import java.util.UUID
import models.Shipment
import play.api.Configuration
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.{Inject, Singleton}

@Singleton
class OrderServiceClient @Inject()(
  ws: WSClient,
  config: Configuration
)(implicit ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  private val orderServiceUrl = config.getOptional[String]("order.service.url")
    .getOrElse("http://order-service.order.svc.cluster.local:8084")
  private val internalApiToken = config.getOptional[String]("order.service.internal.api.token")
  
  private def getAuthHeader: Option[(String, String)] = {
    internalApiToken.map(token => "Authorization" -> s"Bearer $token")
  }
  
  /**
   * Gets a shipment by ID from the Order Service.
   */
  def getShipment(shipmentId: UUID): Future[Option[Shipment]] = {
    val headers = getAuthHeader.toSeq
    if (headers.isEmpty) {
      logger.error("Order service internal API token not configured")
      Future.failed(new IllegalStateException("Order service internal API token not configured"))
    } else {
      ws.url(s"$orderServiceUrl/internal/shipments/$shipmentId")
        .addHttpHeaders(headers: _*)
        .get()
        .map { response =>
          if (response.status == 200) {
            val json = response.json
            Some(parseShipment(json))
          } else if (response.status == 404) {
            None
          } else {
            logger.error(s"Failed to get shipment: ${response.status} - ${response.body}")
            throw new IllegalStateException(s"Failed to get shipment: ${response.status}")
          }
        }
        .recoverWith { case e =>
          logger.error(s"Error calling Order Service to get shipment $shipmentId", e)
          Future.failed(new IllegalStateException(s"Failed to get shipment: ${e.getMessage}", e))
        }
    }
  }
  
  /**
   * Gets all shipments for an order from the Order Service.
   */
  def getShipmentsByOrder(orderId: UUID): Future[List[Shipment]] = {
    val headers = getAuthHeader.toSeq
    if (headers.isEmpty) {
      logger.error("Order service internal API token not configured")
      Future.failed(new IllegalStateException("Order service internal API token not configured"))
    } else {
      ws.url(s"$orderServiceUrl/internal/orders/$orderId/shipments")
        .addHttpHeaders(headers: _*)
        .get()
        .map { response =>
          if (response.status == 200) {
            val json = response.json
            json.as[List[JsValue]].map(parseShipment)
          } else {
            logger.error(s"Failed to get shipments for order: ${response.status} - ${response.body}")
            throw new IllegalStateException(s"Failed to get shipments: ${response.status}")
          }
        }
        .recoverWith { case e =>
          logger.error(s"Error calling Order Service to get shipments for order $orderId", e)
          Future.failed(new IllegalStateException(s"Failed to get shipments: ${e.getMessage}", e))
        }
    }
  }
  
  /**
   * Updates shipment status in the Order Service.
   */
  def updateShipmentStatus(shipmentId: UUID, status: String, shippedAt: Option[Instant] = None, deliveredAt: Option[Instant] = None): Future[Shipment] = {
    val headers = getAuthHeader.toSeq
    if (headers.isEmpty) {
      logger.error("Order service internal API token not configured")
      Future.failed(new IllegalStateException("Order service internal API token not configured"))
    } else {
      val requestBody = Json.obj(
        "status" -> status,
        "shipped_at" -> shippedAt.map(_.toString),
        "delivered_at" -> deliveredAt.map(_.toString)
      )
      
      ws.url(s"$orderServiceUrl/internal/shipments/$shipmentId/status")
        .addHttpHeaders(headers: _*)
        .put(requestBody)
        .map { response =>
          if (response.status == 200) {
            parseShipment(response.json)
          } else {
            logger.error(s"Failed to update shipment status: ${response.status} - ${response.body}")
            throw new IllegalStateException(s"Failed to update shipment status: ${response.status}")
          }
        }
        .recoverWith { case e =>
          logger.error(s"Error calling Order Service to update shipment status for $shipmentId", e)
          Future.failed(new IllegalStateException(s"Failed to update shipment status: ${e.getMessage}", e))
        }
    }
  }
  
  /**
   * Gets all shipments for a seller from the Order Service.
   */
  def getShipmentsBySeller(sellerId: String, limit: Int = 50, offset: Int = 0): Future[List[Shipment]] = {
    val headers = getAuthHeader.toSeq
    if (headers.isEmpty) {
      logger.error("Order service internal API token not configured")
      Future.failed(new IllegalStateException("Order service internal API token not configured"))
    } else {
      ws.url(s"$orderServiceUrl/internal/sellers/$sellerId/shipments")
        .addHttpHeaders(headers: _*)
        .addQueryStringParameters("limit" -> limit.toString, "offset" -> offset.toString)
        .get()
        .map { response =>
          if (response.status == 200) {
            val json = response.json
            json.as[List[JsValue]].map(parseShipment)
          } else {
            logger.error(s"Failed to get shipments for seller: ${response.status} - ${response.body}")
            throw new IllegalStateException(s"Failed to get shipments: ${response.status}")
          }
        }
        .recoverWith { case e =>
          logger.error(s"Error calling Order Service to get shipments for seller $sellerId", e)
          Future.failed(new IllegalStateException(s"Failed to get shipments: ${e.getMessage}", e))
        }
    }
  }
  
  /**
   * Gets a shipment by tracking number from the Order Service.
   */
  def getShipmentByTrackingNumber(trackingNumber: String): Future[Option[Shipment]] = {
    val headers = getAuthHeader.toSeq
    if (headers.isEmpty) {
      logger.error("Order service internal API token not configured")
      Future.failed(new IllegalStateException("Order service internal API token not configured"))
    } else {
      ws.url(s"$orderServiceUrl/internal/shipments/by-tracking/$trackingNumber")
        .addHttpHeaders(headers: _*)
        .get()
        .map { response =>
          if (response.status == 200) {
            val json = response.json
            Some(parseShipment(json))
          } else if (response.status == 404) {
            None
          } else {
            logger.error(s"Failed to get shipment by tracking: ${response.status} - ${response.body}")
            throw new IllegalStateException(s"Failed to get shipment: ${response.status}")
          }
        }
        .recoverWith { case e =>
          logger.error(s"Error calling Order Service to get shipment by tracking $trackingNumber", e)
          Future.failed(new IllegalStateException(s"Failed to get shipment: ${e.getMessage}", e))
        }
    }
  }
  
  /**
   * Updates shipment tracking information in the Order Service.
   */
  def updateShipmentTracking(shipmentId: UUID, trackingNumber: String, carrier: String): Future[Shipment] = {
    val headers = getAuthHeader.toSeq
    if (headers.isEmpty) {
      logger.error("Order service internal API token not configured")
      Future.failed(new IllegalStateException("Order service internal API token not configured"))
    } else {
      val requestBody = Json.obj(
        "tracking_number" -> trackingNumber,
        "carrier" -> carrier
      )
      
      ws.url(s"$orderServiceUrl/internal/shipments/$shipmentId/tracking")
        .addHttpHeaders(headers: _*)
        .put(requestBody)
        .map { response =>
          if (response.status == 200) {
            parseShipment(response.json)
          } else {
            logger.error(s"Failed to update shipment tracking: ${response.status} - ${response.body}")
            throw new IllegalStateException(s"Failed to update shipment tracking: ${response.status}")
          }
        }
        .recoverWith { case e =>
          logger.error(s"Error calling Order Service to update shipment tracking for $shipmentId", e)
          Future.failed(new IllegalStateException(s"Failed to update shipment tracking: ${e.getMessage}", e))
        }
    }
  }
  
  private def parseShipment(json: JsValue): Shipment = {
    Shipment(
      id = UUID.fromString((json \ "id").as[String]),
      orderId = UUID.fromString((json \ "order_id").as[String]),
      sellerId = (json \ "seller_id").as[String],
      status = (json \ "status").as[String],
      trackingNumber = (json \ "tracking_number").asOpt[String],
      carrier = (json \ "carrier").asOpt[String],
      shippedAt = (json \ "shipped_at").asOpt[String].map(Instant.parse),
      deliveredAt = (json \ "delivered_at").asOpt[String].map(Instant.parse),
      createdAt = Instant.parse((json \ "created_at").as[String]),
      updatedAt = Instant.parse((json \ "updated_at").as[String])
    )
  }
}
