package repositories

import anorm._
import anorm.SqlParser._
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import javax.inject.Inject
import models.{Cart, CartEntity, CartItem, CartItemEntity, CartStatus}
import play.api.db.Database
import play.api.libs.json.{Json, Reads, Writes}
import services.RedisService
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class CartRepository @Inject()(
  db: Database,
  redis: RedisService
)(implicit ec: ExecutionContext) {
  
  private val CART_TTL_SECONDS = 15 * 60  // 15 minutes
  private val REDIS_KEY_PREFIX = "cart:"
  private val REDIS_CART_ID_KEY_PREFIX = "cart-id:"  // Reverse mapping: cartId -> cart
  
  // Redis cart JSON format
  implicit val cartItemReads: Reads[CartItem] = Json.reads[CartItem]
  implicit val cartItemWrites: Writes[CartItem] = Json.writes[CartItem]
  implicit val cartReads: Reads[Cart] = Json.reads[Cart]
  implicit val cartWrites: Writes[Cart] = Json.writes[Cart]
  
  // Get active cart from Redis
  def getActiveCart(buyerId: String): Future[Option[Cart]] = Future {
    val key = s"$REDIS_KEY_PREFIX$buyerId"
    redis.get[Cart](key)
  }
  
  // Get active cart from Redis by cart ID (for internal API)
  // Uses reverse mapping: cart-id:{cartId} -> cart
  def getActiveCartByCartId(cartId: UUID): Future[Option[Cart]] = Future {
    val key = s"$REDIS_CART_ID_KEY_PREFIX$cartId"
    redis.get[Cart](key)
  }
  
  // Get cart from PostgreSQL by cart ID
  def getCartByCartId(cartId: UUID): Future[Option[CartEntity]] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        SELECT id, buyer_id, status, created_at, updated_at, completed_at
        FROM carts
        WHERE id = CAST(${cartId.toString} AS uuid)
      """.as(cartEntityParser.singleOpt)
    }
  }
  
  // Save active cart to Redis with TTL
  // Stores cart by both buyerId (for normal lookups) and cartId (for reverse lookups)
  // todo-vh: Expired carts cannot be recovered - consider increased TTL or persisting earlier. Consider publish events on cart expiry and tracking analytics/recovery via them. Can also persist expired carts in a separate table for analytics/recovery.
  def saveActiveCart(cart: Cart): Future[Boolean] = Future {
    val expiresAt = cart.expiresAt.getOrElse(Instant.now().plusSeconds(CART_TTL_SECONDS))
    val ttlSeconds = (expiresAt.getEpochSecond - Instant.now().getEpochSecond).toInt.max(1)
    
    // Store by buyerId (for normal lookups)
    val buyerKey = s"$REDIS_KEY_PREFIX${cart.buyerId}"
    redis.set(buyerKey, cart, Some(ttlSeconds))
    
    // Store by cartId (for reverse lookups - used by inventory service)
    val cartIdKey = s"$REDIS_CART_ID_KEY_PREFIX${cart.cartId}"
    redis.set(cartIdKey, cart, Some(ttlSeconds))
    
    true
  }
  
  // Extend cart TTL
  def extendCartTTL(buyerId: String): Future[Boolean] = Future {
    val key = s"$REDIS_KEY_PREFIX$buyerId"
    val cartOpt = redis.get[Cart](key)
    cartOpt.foreach { cart =>
      // Extend TTL for both keys
      redis.expire(key, CART_TTL_SECONDS)
      val cartIdKey = s"$REDIS_CART_ID_KEY_PREFIX${cart.cartId}"
      redis.expire(cartIdKey, CART_TTL_SECONDS)
    }
    cartOpt.isDefined
  }
  
  // Delete active cart from Redis
  def deleteActiveCart(buyerId: String): Future[Boolean] = Future {
    val key = s"$REDIS_KEY_PREFIX$buyerId"
    val cartOpt = redis.get[Cart](key)
    cartOpt.foreach { cart =>
      // Delete both keys
      redis.delete(key)
      val cartIdKey = s"$REDIS_CART_ID_KEY_PREFIX${cart.cartId}"
      redis.delete(cartIdKey)
    }
    cartOpt.isDefined
  }
  
  // Move cart from Redis to PostgreSQL (for checkout/completion)
  def persistCart(cart: Cart, status: String): Future[CartEntity] = Future {
    db.withConnection { implicit conn =>
      val now = Instant.now()
      val cartId = cart.cartId
      val buyerId = cart.buyerId
      
      // Insert cart
      SQL"""
        INSERT INTO carts (id, buyer_id, status, created_at, updated_at, completed_at)
        VALUES (CAST(${cartId.toString} AS uuid), $buyerId, $status, $now, $now, ${if (status == "COMPLETED") Some(now) else None})
        RETURNING id, buyer_id, status, created_at, updated_at, completed_at
      """.as(cartEntityParser.single)
    }
  }
  
  // Save cart items to PostgreSQL
  def persistCartItems(cartId: UUID, items: List[CartItem]): Future[Unit] = Future {
    db.withConnection { implicit conn =>
      val now = Instant.now()
      items.foreach { item =>
        item.reservationId match {
          case Some(reservationId) =>
            SQL"""
              INSERT INTO cart_items (id, cart_id, product_id, sku, seller_id, quantity, price_cents, currency, reservation_id, created_at)
              VALUES (CAST(${item.itemId.toString} AS uuid), CAST(${cartId.toString} AS uuid), CAST(${item.productId.toString} AS uuid), 
                      ${item.sku}, ${item.sellerId}, ${item.quantity}, ${item.priceCents}, ${item.currency}, 
                      CAST(${reservationId.toString} AS uuid), $now)
            """.execute()
          case None =>
            SQL"""
              INSERT INTO cart_items (id, cart_id, product_id, sku, seller_id, quantity, price_cents, currency, reservation_id, created_at)
              VALUES (CAST(${item.itemId.toString} AS uuid), CAST(${cartId.toString} AS uuid), CAST(${item.productId.toString} AS uuid), 
                      ${item.sku}, ${item.sellerId}, ${item.quantity}, ${item.priceCents}, ${item.currency}, 
                      NULL, $now)
            """.execute()
        }
      }
    }
  }
  
  // Get cart history from PostgreSQL
  def getCartHistory(buyerId: String, limit: Int = 50): Future[List[CartEntity]] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        SELECT c.id, c.buyer_id, c.status, c.created_at, c.updated_at, c.completed_at
        FROM carts c
        WHERE c.buyer_id = $buyerId
        ORDER BY c.created_at DESC
        LIMIT $limit
      """.as(cartEntityParser.*)
    }
  }
  
  // Get cart with items from PostgreSQL
  def getCartWithItems(cartId: UUID): Future[Option[(CartEntity, List[CartItemEntity])]] = Future {
    db.withConnection { implicit conn =>
      val cartOpt = SQL"""
        SELECT id, buyer_id, status, created_at, updated_at, completed_at
        FROM carts
        WHERE id = CAST(${cartId.toString} AS uuid)
      """.as(cartEntityParser.singleOpt)
      
      cartOpt.map { cart =>
        val items = SQL"""
          SELECT id, cart_id, product_id, sku, seller_id, quantity, price_cents, currency, reservation_id, created_at
          FROM cart_items
          WHERE cart_id = CAST(${cartId.toString} AS uuid)
          ORDER BY created_at
        """.as(cartItemEntityParser.*)
        (cart, items)
      }
    }
  }
  
  // Update cart status in PostgreSQL
  def updateCartStatus(cartId: UUID, status: String): Future[Option[CartEntity]] = Future {
    db.withConnection { implicit conn =>
      val now = Instant.now()
      
      // Use conditional SQL to handle completed_at properly
      if (status == "COMPLETED") {
        SQL"""
          UPDATE carts
          SET status = $status,
              updated_at = $now,
              completed_at = $now
          WHERE id = CAST(${cartId.toString} AS uuid)
          RETURNING id, buyer_id, status, created_at, updated_at, completed_at
        """.as(cartEntityParser.singleOpt)
      } else {
        // For non-COMPLETED status, only update status and updated_at, leave completed_at unchanged
        SQL"""
          UPDATE carts
          SET status = $status,
              updated_at = $now
          WHERE id = CAST(${cartId.toString} AS uuid)
          RETURNING id, buyer_id, status, created_at, updated_at, completed_at
        """.as(cartEntityParser.singleOpt)
      }
    }
  }
  
  // Find abandoned carts (CHECKOUT status, older than specified duration)
  def findAbandonedCarts(olderThan: Instant): Future[List[CartEntity]] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        SELECT id, buyer_id, status, created_at, updated_at, completed_at
        FROM carts
        WHERE status = 'CHECKOUT'
          AND created_at < $olderThan
        ORDER BY created_at ASC
      """.as(cartEntityParser.*)
    }
  }
  
  private val cartEntityParser = {
    get[UUID]("id") ~
    get[String]("buyer_id") ~
    get[String]("status") ~
    get[Instant]("created_at") ~
    get[Instant]("updated_at") ~
    get[Option[Instant]]("completed_at") map {
      case id ~ buyerId ~ status ~ createdAt ~ updatedAt ~ completedAt =>
        CartEntity(id, buyerId, status, createdAt, updatedAt, completedAt)
    }
  }
  
  private val cartItemEntityParser = {
    get[UUID]("id") ~
    get[UUID]("cart_id") ~
    get[UUID]("product_id") ~
    get[String]("sku") ~
    get[String]("seller_id") ~
    get[Int]("quantity") ~
    get[Long]("price_cents") ~
    get[String]("currency") ~
    get[Option[UUID]]("reservation_id") ~
    get[Instant]("created_at") map {
      case id ~ cartId ~ productId ~ sku ~ sellerId ~ quantity ~ priceCents ~ currency ~ reservationId ~ createdAt =>
        CartItemEntity(
          id, cartId, productId, sku, sellerId, quantity, priceCents, currency,
          reservationId,
          createdAt
        )
    }
  }
}

