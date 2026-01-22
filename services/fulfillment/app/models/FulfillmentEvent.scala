package models

import java.time.Instant
import java.util.UUID
import play.api.libs.json.{Json, Writes}

sealed trait FulfillmentEvent {
  def eventId: UUID
  def shipmentId: UUID
  def orderId: UUID
  def sellerId: String
  def createdAt: Instant
  def eventType: String
}

case class ShipmentStatusUpdatedEvent(
  eventId: UUID,
  shipmentId: UUID,
  orderId: UUID,
  sellerId: String,
  status: String,
  previousStatus: String,
  createdAt: Instant
) extends FulfillmentEvent {
  val eventType = "SHIPMENT_STATUS_UPDATED"
}

case class ShipmentTrackingUpdatedEvent(
  eventId: UUID,
  shipmentId: UUID,
  orderId: UUID,
  sellerId: String,
  trackingNumber: String,
  carrier: String,
  createdAt: Instant
) extends FulfillmentEvent {
  val eventType = "SHIPMENT_TRACKING_UPDATED"
}

case class ShipmentShippedEvent(
  eventId: UUID,
  shipmentId: UUID,
  orderId: UUID,
  sellerId: String,
  trackingNumber: String,
  carrier: String,
  shippedAt: Instant,
  createdAt: Instant
) extends FulfillmentEvent {
  val eventType = "SHIPMENT_SHIPPED"
}

case class ShipmentDeliveredEvent(
  eventId: UUID,
  shipmentId: UUID,
  orderId: UUID,
  sellerId: String,
  deliveredAt: Instant,
  createdAt: Instant
) extends FulfillmentEvent {
  val eventType = "SHIPMENT_DELIVERED"
}

object FulfillmentEvent {
  implicit val shipmentStatusUpdatedEventWrites: Writes[ShipmentStatusUpdatedEvent] = Json.writes[ShipmentStatusUpdatedEvent]
  implicit val shipmentTrackingUpdatedEventWrites: Writes[ShipmentTrackingUpdatedEvent] = Json.writes[ShipmentTrackingUpdatedEvent]
  implicit val shipmentShippedEventWrites: Writes[ShipmentShippedEvent] = Json.writes[ShipmentShippedEvent]
  implicit val shipmentDeliveredEventWrites: Writes[ShipmentDeliveredEvent] = Json.writes[ShipmentDeliveredEvent]
  
  implicit val fulfillmentEventWrites: Writes[FulfillmentEvent] = Writes[FulfillmentEvent] {
    case e: ShipmentStatusUpdatedEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
    case e: ShipmentTrackingUpdatedEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
    case e: ShipmentShippedEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
    case e: ShipmentDeliveredEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
  }
}
