package models

import java.time.Instant
import java.util.UUID

sealed trait CartStatus {
  def value: String
}

object CartStatus {
  case object ACTIVE extends CartStatus { val value = "ACTIVE" }
  case object CHECKOUT extends CartStatus { val value = "CHECKOUT" }
  case object COMPLETED extends CartStatus { val value = "COMPLETED" }
  case object ABANDONED extends CartStatus { val value = "ABANDONED" }
  case object EXPIRED extends CartStatus { val value = "EXPIRED" }

  def fromString(s: String): Option[CartStatus] = s match {
    case "ACTIVE" => Some(ACTIVE)
    case "CHECKOUT" => Some(CHECKOUT)
    case "COMPLETED" => Some(COMPLETED)
    case "ABANDONED" => Some(ABANDONED)
    case "EXPIRED" => Some(EXPIRED)
    case _ => None
  }
}

case class CartItem(
  itemId: UUID,
  productId: UUID,
  sku: String,
  sellerId: String,
  quantity: Int,
  priceCents: Long,
  currency: String,
  reservationId: Option[UUID] = None
)

case class Cart(
  cartId: UUID,
  buyerId: String,  // Auth0 user_id (UUID string)
  status: CartStatus,
  items: List[CartItem],
  createdAt: Instant,
  updatedAt: Instant,
  expiresAt: Option[Instant] = None  // For Redis TTL
)

// PostgreSQL model (for cart history)
case class CartEntity(
  id: UUID,
  buyerId: String,
  status: String,  // CHECKOUT, COMPLETED, ABANDONED, EXPIRED
  createdAt: Instant,
  updatedAt: Instant,
  completedAt: Option[Instant]
)

case class CartItemEntity(
  id: UUID,
  cartId: UUID,
  productId: UUID,
  sku: String,
  sellerId: String,
  quantity: Int,
  priceCents: Long,
  currency: String,
  reservationId: Option[UUID],
  createdAt: Instant
)

