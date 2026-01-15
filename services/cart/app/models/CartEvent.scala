package models

import java.time.Instant
import java.util.UUID
import play.api.libs.json.{Json, Writes}

sealed trait CartEvent {
  def eventId: UUID
  def cartId: UUID
  def buyerId: String
  def createdAt: Instant
  def eventType: String
}

case class CartCreatedEvent(
  eventId: UUID,
  cartId: UUID,
  buyerId: String,
  createdAt: Instant
) extends CartEvent {
  val eventType = "CART_CREATED"
}

case class CartItemAddedEvent(
  eventId: UUID,
  cartId: UUID,
  buyerId: String,
  itemId: UUID,
  productId: UUID,
  quantity: Int,
  createdAt: Instant
) extends CartEvent {
  val eventType = "CART_ITEM_ADDED"
}

case class CartCheckoutInitiatedEvent(
  eventId: UUID,
  cartId: UUID,
  buyerId: String,
  orderId: UUID,
  itemCount: Int,
  totalAmountCents: Long,
  createdAt: Instant
) extends CartEvent {
  val eventType = "CART_CHECKOUT_INITIATED"
}

case class CartCompletedEvent(
  eventId: UUID,
  cartId: UUID,
  buyerId: String,
  orderId: UUID,
  createdAt: Instant
) extends CartEvent {
  val eventType = "CART_COMPLETED"
}

case class CartAbandonedEvent(
  eventId: UUID,
  cartId: UUID,
  buyerId: String,
  reason: String,
  createdAt: Instant
) extends CartEvent {
  val eventType = "CART_ABANDONED"
}

object CartEvent {
  implicit val cartCreatedEventWrites: Writes[CartCreatedEvent] = Json.writes[CartCreatedEvent]
  implicit val cartItemAddedEventWrites: Writes[CartItemAddedEvent] = Json.writes[CartItemAddedEvent]
  implicit val cartCheckoutInitiatedEventWrites: Writes[CartCheckoutInitiatedEvent] = Json.writes[CartCheckoutInitiatedEvent]
  implicit val cartCompletedEventWrites: Writes[CartCompletedEvent] = Json.writes[CartCompletedEvent]
  implicit val cartAbandonedEventWrites: Writes[CartAbandonedEvent] = Json.writes[CartAbandonedEvent]
  
  implicit val cartEventWrites: Writes[CartEvent] = Writes[CartEvent] {
    case e: CartCreatedEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
    case e: CartItemAddedEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
    case e: CartCheckoutInitiatedEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
    case e: CartCompletedEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
    case e: CartAbandonedEvent => Json.toJson(e).as[play.api.libs.json.JsObject] + ("type" -> Json.toJson(e.eventType))
  }
}

