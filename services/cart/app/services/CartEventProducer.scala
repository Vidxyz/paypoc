package services

import java.time.Instant
import java.util.UUID
import models.{Cart, CartAbandonedEvent, CartCheckoutInitiatedEvent, CartCompletedEvent, CartCreatedEvent, CartEvent, CartItem, CartItemAddedEvent}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import play.api.Configuration
import play.api.Logger
import play.api.libs.json.Json
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import javax.inject.{Inject, Singleton}
import java.util.Properties

@Singleton
class CartEventProducer @Inject()(
  config: Configuration
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)
  
  private val bootstrapServers = config.get[String]("kafka.bootstrap.servers")
  private val topic = config.get[String]("kafka.topics.cart.events")

  private lazy val producer: KafkaProducer[String, String] = {
    val props = new Properties()
    props.put("bootstrap.servers", bootstrapServers)
    props.put("key.serializer", classOf[StringSerializer].getName)
    props.put("value.serializer", classOf[StringSerializer].getName)
    props.put("acks", "all")
    props.put("retries", "3")
    new KafkaProducer[String, String](props)
  }

  def publishCartCreatedEvent(cart: Cart): Future[Unit] = {
    Future {
      val event = CartCreatedEvent(
        eventId = UUID.randomUUID(),
        cartId = cart.cartId,
        buyerId = cart.buyerId,
        createdAt = Instant.now()
      )
      publishEvent(event, cart.buyerId)
    }
  }

  def publishCartItemAddedEvent(cart: Cart, item: CartItem, quantity: Int): Future[Unit] = {
    Future {
      val event = CartItemAddedEvent(
        eventId = UUID.randomUUID(),
        cartId = cart.cartId,
        buyerId = cart.buyerId,
        itemId = item.itemId,
        productId = item.productId,
        quantity = quantity,
        createdAt = Instant.now()
      )
      publishEvent(event, cart.buyerId)
    }
  }

  def publishCartCheckoutInitiatedEvent(cart: Cart, orderId: UUID): Future[Unit] = {
    Future {
      val totalAmountCents = cart.items.map(item => item.priceCents * item.quantity).sum
      val event = CartCheckoutInitiatedEvent(
        eventId = UUID.randomUUID(),
        cartId = cart.cartId,
        buyerId = cart.buyerId,
        orderId = orderId,
        itemCount = cart.items.length,
        totalAmountCents = totalAmountCents,
        createdAt = Instant.now()
      )
      publishEvent(event, cart.buyerId)
    }
  }

  def publishCartCompletedEvent(cartId: UUID, buyerId: String, orderId: UUID): Future[Unit] = {
    Future {
      val event = CartCompletedEvent(
        eventId = UUID.randomUUID(),
        cartId = cartId,
        buyerId = buyerId,
        orderId = orderId,
        createdAt = Instant.now()
      )
      publishEvent(event, buyerId)
    }
  }

  def publishCartAbandonedEvent(cartId: UUID, buyerId: String, reason: String): Future[Unit] = {
    Future {
      val event = CartAbandonedEvent(
        eventId = UUID.randomUUID(),
        cartId = cartId,
        buyerId = buyerId,
        reason = reason,
        createdAt = Instant.now()
      )
      publishEvent(event, buyerId)
    }
  }

  private def publishEvent(event: CartEvent, key: String): Unit = {
    Try {
      val json = Json.toJson(event).toString()
      val record = new ProducerRecord[String, String](topic, key, json)
      producer.send(record).get()
      logger.debug(s"Published cart event: ${event.eventType} for cart ${event.cartId}")
    }.recover { case e: Exception =>
      logger.error(s"Failed to publish cart event: ${event.eventType}", e)
      throw new RuntimeException(s"Failed to publish cart event: ${event.eventType}: ${e.getMessage}", e)
    }
  }
}

