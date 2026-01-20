package services

import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import models.{AccountType, Cart, CartEntity, CartItem, CartItemEntity, CartStatus}
import play.api.Configuration
import play.api.Logger
import play.api.libs.json.{Json, JsNull, JsValue}
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
  private val inventoryInternalApiToken = config.getOptional[String]("inventory.service.internal.api.token")
  private val catalogServiceUrl = config.getOptional[String]("catalog.service.url").getOrElse("http://catalog-service.catalog.svc.cluster.local:8082")
  private val orderServiceUrl = config.getOptional[String]("order.service.url").getOrElse("http://order-service.order.svc.cluster.local:8084")
  private val orderInternalApiToken = config.getOptional[String]("order.service.internal.api.token")
  private val abandonmentTimeoutSeconds = config.getOptional[Int]("cart.abandonment.timeout.seconds").getOrElse(3600) // Default: 1 hour
  
  // Helper to get internal API authorization header for inventory service
  private def getInternalAuthHeader: Option[(String, String)] = {
    inventoryInternalApiToken.map(token => "Authorization" -> s"Bearer $token")
  }
  
  // Helper to get internal API authorization header for order service
  private def getOrderInternalAuthHeader: Option[(String, String)] = {
    orderInternalApiToken.map(token => "Authorization" -> s"Bearer $token")
  }
  
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
    // Get product details from Catalog Service (public endpoint, no auth needed)
    getProductDetails(productId).flatMap { productOpt =>
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
              
              // Create soft reservation in Inventory Service (using internal API)
              createSoftReservation(productId, cartId, quantity).flatMap { reservationIdOpt =>
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
        // Update reservation in Inventory Service (using internal API)
        updateReservation(item, newQuantity, cart.cartId).flatMap { newReservationIdOpt =>
          val updatedItem = item.copy(
            quantity = newQuantity,
            reservationId = newReservationIdOpt.orElse(item.reservationId) // Keep old reservation if new one failed
          )
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
      
      // Release reservation in Inventory Service (if exists, using internal API)
      val releaseFuture = item.reservationId match {
        case Some(reservationId) => releaseReservation(reservationId)
        case None => Future.successful(())
      }
      
      releaseFuture.flatMap { _ =>
        val updatedItems = cart.items.filterNot(_.itemId == itemId)
        val updatedCart = cart.copy(
          items = updatedItems,
          updatedAt = Instant.now(),
          expiresAt = Some(Instant.now().plusSeconds(15 * 60))
        )
        
        cartRepository.saveActiveCart(updatedCart).map(_ => updatedCart)
      }
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
              val orderRequest = Json.obj(
                "cart_id" -> cart.cartId.toString,
                "buyer_id" -> buyerId,
                "items" -> Json.toJson(cart.items.map { item =>
                  Json.obj(
                    "item_id" -> item.itemId.toString,
                    "product_id" -> item.productId.toString,
                    "sku" -> item.sku,
                    "seller_id" -> item.sellerId,
                    "quantity" -> item.quantity,
                    "price_cents" -> item.priceCents,
                    "currency" -> item.currency,
                    "reservation_id" -> item.reservationId.map(id => Json.toJsFieldJsValueWrapper(id.toString)).getOrElse(Json.toJsFieldJsValueWrapper(JsNull))
                  )
                })
              )
              
              val headers = getOrderInternalAuthHeader.toSeq
              if (headers.isEmpty) {
                logger.error("Order service internal API token not configured")
                Future.failed(new IllegalStateException("Order service internal API token not configured"))
              } else {
                ws.url(s"$orderServiceUrl/internal/orders")
                  .addHttpHeaders(headers: _*)
                  .post(orderRequest)
                  .map { response =>
                    if (response.status == 201) {
                      val orderResponse = response.json
                      val orderId = UUID.fromString((orderResponse \ "order_id").as[String])
                      val paymentId = UUID.fromString((orderResponse \ "payment_id").as[String])
                      val clientSecret = (orderResponse \ "client_secret").as[String]
                      val checkoutUrl = (orderResponse \ "checkout_url").asOpt[String].getOrElse(s"https://buyit.local/checkout?orderId=$orderId")
                      
                      cartEventProducer.publishCartCheckoutInitiatedEvent(cart, orderId)
                      
                      models.CheckoutResponse(
                        orderId = orderId,
                        paymentId = paymentId,
                        clientSecret = clientSecret,
                        checkoutUrl = checkoutUrl
                      )
                    } else if (response.status == 401) {
                      logger.error(s"Unauthorized: Invalid or missing internal API token for order service")
                      throw new IllegalStateException("Unauthorized: Invalid internal API token")
                    } else {
                      logger.error(s"Failed to create order: ${response.status} - ${response.body}")
                      throw new IllegalStateException(s"Failed to create order: ${response.status} - ${response.body}")
                    }
                  }
                  .recoverWith { case e =>
                    logger.error("Error calling Order Service to create order", e)
                    Future.failed(new IllegalStateException(s"Failed to create order: ${e.getMessage}", e))
                  }
              }
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
  
  // Update cart status (internal API)
  def updateCartStatus(cartId: UUID, status: String): Future[Option[CartEntity]] = {
    cartRepository.updateCartStatus(cartId, status).map { cartEntityOpt =>
      cartEntityOpt.foreach { cartEntity =>
        // Publish event based on status
        status match {
          case "COMPLETED" =>
            cartEventProducer.publishCartCompletedEvent(cartId, cartEntity.buyerId, cartId) // orderId is cartId for now
          case "ABANDONED" =>
            cartEventProducer.publishCartAbandonedEvent(cartId, cartEntity.buyerId, "Status updated to ABANDONED")
          case _ =>
            // No event for other status updates
        }
      }
      cartEntityOpt
    }
  }
  
  // Cleanup abandoned carts (CHECKOUT status, older than configured timeout)
  // Releases inventory reservations and marks carts as ABANDONED
  def cleanupAbandonedCarts(): Future[CleanupResult] = {
    logger.info(s"=== Starting cleanupAbandonedCarts ===")
    logger.info(s"Abandonment timeout: ${abandonmentTimeoutSeconds} seconds (${abandonmentTimeoutSeconds / 60} minutes)")
    
    val now = Instant.now()
    val timeoutAgo = now.minusSeconds(abandonmentTimeoutSeconds)
    logger.info(s"Current time: $now")
    logger.info(s"Looking for carts in CHECKOUT status created before: $timeoutAgo")
    
    cartRepository.findAbandonedCarts(timeoutAgo).flatMap { abandonedCarts =>
      logger.info(s"=== Found ${abandonedCarts.size} abandoned cart(s) to cleanup ===")
      if (abandonedCarts.nonEmpty) {
        abandonedCarts.zipWithIndex.foreach { case (cart, idx) =>
          logger.info(s"  Cart ${idx + 1}/${abandonedCarts.size}: id=${cart.id}, buyerId=${cart.buyerId}, status=${cart.status}, createdAt=${cart.createdAt}")
        }
      }
      
      val futures = abandonedCarts.map { cart =>
        logger.info(s"Processing abandoned cart: id=${cart.id}, buyerId=${cart.buyerId}")
        // Get cart items with reservations
        cartRepository.getCartWithItems(cart.id).flatMap {
          case Some((cartEntity, items)) =>
            logger.info(s"Cart ${cart.id} has ${items.size} item(s)")
            val reservations = items.flatMap(_.reservationId)
            logger.info(s"Cart ${cart.id} has ${reservations.size} reservation(s) to release: ${reservations.map(_.toString).mkString(", ")}")
            
            // Release all reservations for this cart
            val reservationFutures = reservations.map { reservationId =>
              logger.info(s"Releasing reservation ${reservationId} for cart ${cart.id}")
              releaseReservation(reservationId)
            }
            
            // Wait for all reservations to be released, then mark cart as ABANDONED
            Future.sequence(reservationFutures).flatMap { _ =>
              logger.info(s"All reservations released for cart ${cart.id}, marking as ABANDONED")
              updateCartStatus(cart.id, "ABANDONED").map { _ =>
                logger.info(s"✓ Successfully marked cart ${cart.id} as ABANDONED and released ${reservationFutures.size} reservation(s)")
                (true, false) // (success, error)
              }
            }.recover { case e =>
              logger.error(s"✗ Error processing abandoned cart ${cart.id}: ${e.getMessage}", e)
              logger.error(s"Stack trace: ${e.getStackTrace.mkString("\n")}")
              (false, true) // (success, error)
            }
          case None =>
            // Cart not found or no items - mark as ABANDONED anyway
            logger.warn(s"Cart ${cart.id} not found or has no items, marking as ABANDONED anyway")
            updateCartStatus(cart.id, "ABANDONED").map { _ =>
              logger.info(s"✓ Marked cart ${cart.id} as ABANDONED (no items found)")
              (true, false) // (success, error)
            }.recover { case e =>
              logger.error(s"✗ Error marking cart ${cart.id} as ABANDONED: ${e.getMessage}", e)
              logger.error(s"Stack trace: ${e.getStackTrace.mkString("\n")}")
              (false, true) // (success, error)
            }
        }
      }
      
      Future.sequence(futures).map { results =>
        val processedCount = results.count(_._1)
        val errorCount = results.count(_._2)
        logger.info(s"=== Cleanup processing complete ===")
        logger.info(s"Total carts found: ${abandonedCarts.size}")
        logger.info(s"Successfully processed: $processedCount")
        logger.info(s"Errors: $errorCount")
        CleanupResult(
          foundCount = abandonedCarts.size,
          processedCount = processedCount,
          errorCount = errorCount
        )
      }
    }
  }
  
  case class CleanupResult(
    foundCount: Int,
    processedCount: Int,
    errorCount: Int
  )
  
  // Helper: Get product details from Catalog Service (public endpoint)
  private def getProductDetails(productId: UUID): Future[Option[JsValue]] = {
    ws.url(s"$catalogServiceUrl/api/catalog/products/$productId")
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
  
  // Helper: Get inventory ID from product ID (using internal API)
  private def getInventoryIdByProductId(productId: UUID): Future[Option[UUID]] = {
    val headers = getInternalAuthHeader.toSeq
    ws.url(s"$inventoryServiceUrl/internal/stock/$productId")
      .addHttpHeaders(headers: _*)
      .get()
      .map { response =>
        if (response.status == 200) {
          val inventory = response.json
          (inventory \ "id").asOpt[String].map(UUID.fromString)
        } else if (response.status == 404) {
          logger.warn(s"Inventory not found for product $productId")
          None
        } else if (response.status == 401) {
          logger.error(s"Unauthorized: Invalid or missing internal API token for inventory service")
          None
        } else {
          logger.warn(s"Failed to get inventory for product $productId: ${response.status}")
          None
        }
      }
      .recover { case e =>
        logger.error(s"Error getting inventory for product $productId from Inventory Service", e)
        None
      }
  }

  // Helper: Create soft reservation in Inventory Service (using internal API)
  private def createSoftReservation(productId: UUID, cartId: UUID, quantity: Int): Future[Option[UUID]] = {
    // First, get inventory ID from product ID
    getInventoryIdByProductId(productId).flatMap {
      case None =>
        logger.warn(s"Cannot create reservation: inventory not found for product $productId")
        Future.successful(None)
      case Some(inventoryId) =>
        // Create soft reservation using internal API
        val headers = getInternalAuthHeader.toSeq
        ws.url(s"$inventoryServiceUrl/internal/reservations")
          .addHttpHeaders(headers: _*)
          .post(Json.obj(
            "inventoryId" -> inventoryId.toString,
            "cartId" -> cartId.toString,
            "quantity" -> quantity
          ))
          .map { response =>
            if (response.status == 201) {
              val reservation = response.json
              (reservation \ "id").asOpt[String].map(UUID.fromString)
            } else if (response.status == 401) {
              logger.error(s"Unauthorized: Invalid or missing internal API token for inventory service")
              None
            } else {
              logger.warn(s"Failed to create reservation for product $productId: ${response.status} - ${response.body}")
              None
            }
          }
          .recover { case e =>
            logger.error(s"Error creating reservation for product $productId", e)
            None
          }
    }
  }
  
  // Helper: Update reservation quantity
  // Since there's no update endpoint, we release the old reservation and create a new one
  private def updateReservation(item: CartItem, newQuantity: Int, cartId: UUID): Future[Option[UUID]] = {
    // If there's an existing reservation, release it first
    val releaseFuture = item.reservationId match {
      case Some(reservationId) =>
        releaseReservation(reservationId).map(_ => ())
      case None =>
        Future.successful(())
    }
    
    // Then create a new reservation with the updated quantity
    releaseFuture.flatMap { _ =>
      createSoftReservation(item.productId, cartId, newQuantity)
    }
  }
  
  // Helper: Release reservation (using internal API)
  // Handles cases where reservation is already in terminal state (SOLD, RELEASED, EXPIRED)
  private def releaseReservation(reservationId: UUID): Future[Unit] = {
    val headers = getInternalAuthHeader.toSeq
    ws.url(s"$inventoryServiceUrl/internal/reservations/$reservationId/release")
      .addHttpHeaders(headers: _*)
      .post(Json.obj())
      .map { response =>
        val responseBody = response.body
        if (response.status == 200) {
          logger.debug(s"Successfully released reservation $reservationId")
        } else if (response.status == 401) {
          logger.error(s"Unauthorized: Invalid or missing internal API token for inventory service")
        } else if (response.status == 404) {
          // Reservation not found - may have already been released or never existed
          logger.warn(s"Reservation $reservationId not found (may have already been released or expired)")
        } else if (response.status == 400) {
          // 400 Bad Request typically means reservation is in a state that can't be released
          // (e.g., SOLD, already RELEASED, or EXPIRED)
          // This is expected for abandoned carts where reservations may have been:
          // - Confirmed as SOLD after payment
          // - Already released by inventory cleanup job
          // - Expired and auto-released
          // We treat this as success (idempotent operation)
          if (responseBody.contains("SOLD") || responseBody.contains("RELEASED") || responseBody.contains("EXPIRED") || 
              responseBody.contains("Cannot release")) {
            logger.debug(s"Reservation $reservationId cannot be released (already in terminal state): $responseBody")
          } else {
            logger.warn(s"Failed to release reservation $reservationId: ${response.status} - $responseBody")
          }
        } else {
          logger.warn(s"Unexpected error releasing reservation $reservationId: ${response.status} - $responseBody")
        }
      }
      .recover { case e =>
        logger.error(s"Error releasing reservation $reservationId: ${e.getMessage}", e)
      }
  }
}

