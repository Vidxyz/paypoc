package models

import java.util.UUID
import play.api.libs.json.{Json, Reads, Writes}

case class AddCartItemRequest(
  productId: UUID,
  quantity: Int
)

object AddCartItemRequest {
  implicit val reads: Reads[AddCartItemRequest] = Json.reads[AddCartItemRequest]
  implicit val writes: Writes[AddCartItemRequest] = Json.writes[AddCartItemRequest]
}

case class UpdateCartItemRequest(
  quantity: Int
)

object UpdateCartItemRequest {
  implicit val reads: Reads[UpdateCartItemRequest] = Json.reads[UpdateCartItemRequest]
  implicit val writes: Writes[UpdateCartItemRequest] = Json.writes[UpdateCartItemRequest]
}

case class CartItemResponse(
  itemId: java.util.UUID,
  productId: java.util.UUID,
  sku: String,
  sellerId: String,
  quantity: Int,
  priceCents: Long,
  currency: String
)

object CartItemResponse {
  implicit val writes: Writes[CartItemResponse] = Json.writes[CartItemResponse]
  
  def fromCartItem(item: CartItem): CartItemResponse = {
    CartItemResponse(
      itemId = item.itemId,
      productId = item.productId,
      sku = item.sku,
      sellerId = item.sellerId,
      quantity = item.quantity,
      priceCents = item.priceCents,
      currency = item.currency
    )
  }
}

case class CartResponse(
  cartId: java.util.UUID,
  buyerId: String,
  status: String,
  items: List[CartItemResponse],
  createdAt: String,
  updatedAt: String,
  expiresAt: Option[String] = None
)

object CartResponse {
  implicit val writes: Writes[CartResponse] = Json.writes[CartResponse]
  
  def fromCart(cart: Cart): CartResponse = {
    CartResponse(
      cartId = cart.cartId,
      buyerId = cart.buyerId,
      status = cart.status.value,
      items = cart.items.map(CartItemResponse.fromCartItem),
      createdAt = cart.createdAt.toString,
      updatedAt = cart.updatedAt.toString,
      expiresAt = cart.expiresAt.map(_.toString)
    )
  }
}

case class CartHistoryResponse(
  cartId: java.util.UUID,
  buyerId: String,
  status: String,
  itemCount: Int,
  totalAmountCents: Long,
  currency: String,
  createdAt: String,
  updatedAt: String,
  completedAt: Option[String]
)

object CartHistoryResponse {
  implicit val writes: Writes[CartHistoryResponse] = Json.writes[CartHistoryResponse]
}

case class CheckoutResponse(
  orderId: java.util.UUID,
  paymentId: java.util.UUID,
  clientSecret: String,
  checkoutUrl: String
)

object CheckoutResponse {
  implicit val writes: Writes[CheckoutResponse] = Json.writes[CheckoutResponse]
}

