package services

import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import models.{AccountType, Cart, CartEntity, CartItem, CartItemEntity, CartStatus}
import play.api.Configuration
import play.api.Logger
import play.api.libs.json.{Json, JsValue}
import play.api.libs.ws.WSClient
import repositories.CartRepository
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class CartService @Inject()(
  cartRepository: CartRepository,
  cartEventProducer: CartEventProducer,
  ws: WSClient,
  config: Configuration
)(implicit ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  private val inventoryServiceUrl = config.getOptional[String]("inventory.service.url").getOrElse("http://inventory-service.inventory.svc.cluster.local:8083")
  private val catalogServiceUrl = config.getOptional[String]("catalog.service.url").getOrElse("http://catalog-service.catalog.svc.cluster.local:8000")
  
  // Get or create cart for buyer
  def getOrCreateCart(buyerId: String): Future[Cart] = {
    cartRepository.getActiveCart(buyerId).flatMap {
      case Some(cart) =>
        // Extend TTL
        cartRepository.extendCartTTL(buyerId).map(_ => cart)
      case None =>
        // Create new cart
        val now = Instant.now()
        val cart = Cart(
          cartId = UUID.randomUUID(),
          buyerId = buyerId,
          status = CartStatus.ACTIVE,
          items = List.empty,
          createdAt = now,
          updatedAt = now,
          expiresAt = Some(now.plusSeconds(15 * 60))
        )
        cartRepository.saveActiveCart(cart).flatMap { _ =>
          cartEventProducer.publishCartCreatedEvent(cart)
          Future.successful(cart)
        }
    }
  }
  
  // Add item to cart
  def addItem(buyerId: String, productId: UUID, quantity: Int, authToken: String): Future[Cart] = {
    // Get product details from Catalog Service
    getProductDetails(productId, authToken).flatMap { productOpt =>
      productOpt match {
        case None => Future.failed(new IllegalArgumentException(s"Product not found: $productId"))
        case Some(product) =>
          // Get or create cart
          getOrCreateCart(buyerId).flatMap { cart =>
            // Check if item already exists
            val existingItemIndex = cart.items.indexWhere(_.productId == productId)
            
            if (existingItemIndex >= 0) {
              // Update existing item quantity
              val existingItem = cart.items(existingItemIndex)
              updateItemQuantity(cart, existingItemIndex, existingItem.quantity + quantity, authToken)
            } else {
              // Add new item
              val itemId = UUID.randomUUID()
              val cartId = cart.cartId
              
              // Create soft reservation in Inventory Service
              createSoftReservation(productId, cartId, quantity, authToken).flatMap { reservationIdOpt =>
                val item = CartItem(
                  itemId = itemId,
                  productId = productId,
                  sku = (product \ "sku").as[String],
                  sellerId = (product \ "seller_id").as[String],
                  quantity = quantity,
                  priceCents = (product \ "price_cents").as[Long],
                  currency = (product \ "currency").as[String],
                  reservationId = reservationIdOpt
                )
                
                val updatedCart = cart.copy(
                  items = cart.items :+ item,
                  updatedAt = Instant.now(),
                  expiresAt = Some(Instant.now().plusSeconds(15 * 60))
                )
                
                cartRepository.saveActiveCart(updatedCart).flatMap { _ =>
                  cartEventProducer.publishCartItemAddedEvent(updatedCart, item, quantity)
                  Future.successful(updatedCart)
                }
              }
            }
          }
      }
    }
  }
  
  // Update item quantity
  def updateItemQuantity(cart: Cart, itemIndex: Int, newQuantity: Int, authToken: String): Future[Cart] = {
    if (newQuantity <= 0) {
      Future.failed(new IllegalArgumentException("Quantity must be greater than 0"))
    } else {
      val item = cart.items(itemIndex)
      val quantityDiff = newQuantity - item.quantity
      
      if (quantityDiff == 0) {
        // No change, just extend TTL
        cartRepository.extendCartTTL(cart.buyerId).map(_ => cart)
      } else {
        // Update reservation in Inventory Service
        updateReservation(item, quantityDiff, authToken).flatMap { _ =>
          val updatedItem = item.copy(quantity = newQuantity)
          val updatedItems = cart.items.updated(itemIndex, updatedItem)
          val updatedCart = cart.copy(
            items = updatedItems,
            updatedAt = Instant.now(),
            expiresAt = Some(Instant.now().plusSeconds(15 * 60))
          )
          
          cartRepository.saveActiveCart(updatedCart).map(_ => updatedCart)
        }
      }
    }
  }
  
  // Remove item from cart
  def removeItem(cart: Cart, itemId: UUID, authToken: String): Future[Cart] = {
    val itemIndex = cart.items.indexWhere(_.itemId == itemId)
    
    if (itemIndex < 0) {
      Future.failed(new NoSuchElementException(s"Cart item not found: $itemId"))
    } else {
      val item = cart.items(itemIndex)
      
      // Release reservation in Inventory Service
      item.reservationId.foreach { reservationId =>
        releaseReservation(reservationId, authToken)
      }
      
      val updatedItems = cart.items.filterNot(_.itemId == itemId)
      val updatedCart = cart.copy(
        items = updatedItems,
        updatedAt = Instant.now(),
        expiresAt = Some(Instant.now().plusSeconds(15 * 60))
      )
      
      cartRepository.saveActiveCart(updatedCart).map(_ => updatedCart)
    }
  }
  
  // Initiate checkout
  def checkout(buyerId: String, authToken: String): Future[models.CheckoutResponse] = {
    getOrCreateCart(buyerId).flatMap { cart =>
      if (cart.items.isEmpty) {
        Future.failed(new IllegalArgumentException("Cannot checkout empty cart"))
      } else {
        // Move cart to PostgreSQL
        cartRepository.persistCart(cart, "CHECKOUT").flatMap { cartEntity =>
          cartRepository.persistCartItems(cart.cartId, cart.items).flatMap { _ =>
            // Delete from Redis
            cartRepository.deleteActiveCart(buyerId).flatMap { _ =>
              // Call Order Service to create provisional order
              // TODO: Implement Order Service call
              // For now, return a placeholder response
              val orderId = UUID.randomUUID()
              val paymentId = UUID.randomUUID()
              
              cartEventProducer.publishCartCheckoutInitiatedEvent(cart, orderId)
              
              Future.successful(models.CheckoutResponse(
                orderId = orderId,
                paymentId = paymentId,
                clientSecret = "placeholder_client_secret",
                checkoutUrl = s"https://buyit.local/checkout?orderId=$orderId"
              ))
            }
          }
        }
      }
    }
  }
  
  // Get cart history
  def getCartHistory(buyerId: String, limit: Int = 50): Future[List[CartEntity]] = {
    cartRepository.getCartHistory(buyerId, limit)
  }
  
  // Helper: Get product details from Catalog Service
  private def getProductDetails(productId: UUID, authToken: String): Future[Option[JsValue]] = {
    ws.url(s"$catalogServiceUrl/api/catalog/products/$productId")
      .addHttpHeaders("Authorization" -> s"Bearer $authToken")
      .get()
      .map { response =>
        if (response.status == 200) {
          Some(response.json)
        } else {
          logger.warn(s"Failed to get product $productId: ${response.status}")
          None
        }
      }
      .recover { case e =>
        logger.error(s"Error getting product $productId from Catalog Service", e)
        None
      }
  }
  
  // Helper: Create soft reservation in Inventory Service
  private def createSoftReservation(productId: UUID, cartId: UUID, quantity: Int, authToken: String): Future[Option[UUID]] = {
    // TODO: Get inventory ID from product
    // For now, we'll need to query Inventory Service to find inventory by productId
    // This is a placeholder - actual implementation needs to find inventoryId first
    Future.successful(None)  // Placeholder
  }
  
  // Helper: Update reservation quantity
  private def updateReservation(item: CartItem, quantityDiff: Int, authToken: String): Future[Unit] = {
    // TODO: Implement reservation update
    Future.successful(())
  }
  
  // Helper: Release reservation
  private def releaseReservation(reservationId: UUID, authToken: String): Future[Unit] = {
    ws.url(s"$inventoryServiceUrl/api/inventory/release")
      .addHttpHeaders("Authorization" -> s"Bearer $authToken")
      .post(Json.obj("reservation_id" -> reservationId.toString))
      .map { response =>
        if (response.status != 200) {
          logger.warn(s"Failed to release reservation $reservationId: ${response.status}")
        }
      }
      .recover { case e =>
        logger.error(s"Error releasing reservation $reservationId", e)
      }
  }
}

