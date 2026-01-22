package models

import java.time.Instant
import java.util.UUID
import play.api.libs.json.{Json, Writes}

case class Shipment(
  id: UUID,
  orderId: UUID,
  sellerId: String,
  status: String,  // PENDING, PROCESSING, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, RETURNED
  trackingNumber: Option[String],
  carrier: Option[String],
  shippedAt: Option[Instant],
  deliveredAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant
)

case class ShipmentResponse(
  id: UUID,
  orderId: UUID,
  sellerId: String,
  status: String,
  trackingNumber: Option[String],
  carrier: Option[String],
  shippedAt: Option[String],
  deliveredAt: Option[String],
  createdAt: String,
  updatedAt: String
)

object ShipmentResponse {
  implicit val writes: Writes[ShipmentResponse] = Json.writes[ShipmentResponse]
  
  def fromShipment(shipment: Shipment): ShipmentResponse = {
    ShipmentResponse(
      id = shipment.id,
      orderId = shipment.orderId,
      sellerId = shipment.sellerId,
      status = shipment.status,
      trackingNumber = shipment.trackingNumber,
      carrier = shipment.carrier,
      shippedAt = shipment.shippedAt.map(_.toString),
      deliveredAt = shipment.deliveredAt.map(_.toString),
      createdAt = shipment.createdAt.toString,
      updatedAt = shipment.updatedAt.toString
    )
  }
}
