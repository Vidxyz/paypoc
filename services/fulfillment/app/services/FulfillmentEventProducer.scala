package services

import java.time.Instant
import java.util.UUID
import models.{FulfillmentEvent, ShipmentDeliveredEvent, ShipmentShippedEvent, ShipmentStatusUpdatedEvent, ShipmentTrackingUpdatedEvent}
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
class FulfillmentEventProducer @Inject()(
  config: Configuration
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)
  
  private val bootstrapServers = config.get[String]("kafka.bootstrap.servers")
  private val topic = config.get[String]("kafka.topics.fulfillment.events")

  private lazy val producer: KafkaProducer[String, String] = {
    val props = new Properties()
    props.put("bootstrap.servers", bootstrapServers)
    props.put("key.serializer", classOf[StringSerializer].getName)
    props.put("value.serializer", classOf[StringSerializer].getName)
    props.put("acks", "all")
    props.put("retries", "3")
    new KafkaProducer[String, String](props)
  }

  def publishShipmentStatusUpdatedEvent(
    shipmentId: UUID,
    orderId: UUID,
    sellerId: String,
    status: String,
    previousStatus: String
  ): Future[Unit] = {
    Future {
      val event = ShipmentStatusUpdatedEvent(
        eventId = UUID.randomUUID(),
        shipmentId = shipmentId,
        orderId = orderId,
        sellerId = sellerId,
        status = status,
        previousStatus = previousStatus,
        createdAt = Instant.now()
      )
      publishEvent(event, sellerId)
    }
  }

  def publishShipmentTrackingUpdatedEvent(
    shipmentId: UUID,
    orderId: UUID,
    sellerId: String,
    trackingNumber: String,
    carrier: String
  ): Future[Unit] = {
    Future {
      val event = ShipmentTrackingUpdatedEvent(
        eventId = UUID.randomUUID(),
        shipmentId = shipmentId,
        orderId = orderId,
        sellerId = sellerId,
        trackingNumber = trackingNumber,
        carrier = carrier,
        createdAt = Instant.now()
      )
      publishEvent(event, sellerId)
    }
  }

  def publishShipmentShippedEvent(
    shipmentId: UUID,
    orderId: UUID,
    sellerId: String,
    trackingNumber: String,
    carrier: String,
    shippedAt: Instant
  ): Future[Unit] = {
    Future {
      val event = ShipmentShippedEvent(
        eventId = UUID.randomUUID(),
        shipmentId = shipmentId,
        orderId = orderId,
        sellerId = sellerId,
        trackingNumber = trackingNumber,
        carrier = carrier,
        shippedAt = shippedAt,
        createdAt = Instant.now()
      )
      publishEvent(event, sellerId)
    }
  }

  def publishShipmentDeliveredEvent(
    shipmentId: UUID,
    orderId: UUID,
    sellerId: String,
    deliveredAt: Instant
  ): Future[Unit] = {
    Future {
      val event = ShipmentDeliveredEvent(
        eventId = UUID.randomUUID(),
        shipmentId = shipmentId,
        orderId = orderId,
        sellerId = sellerId,
        deliveredAt = deliveredAt,
        createdAt = Instant.now()
      )
      publishEvent(event, sellerId)
    }
  }

  private def publishEvent(event: FulfillmentEvent, key: String): Unit = {
    Try {
      val json = Json.toJson(event).toString()
      val record = new ProducerRecord[String, String](topic, key, json)
      producer.send(record).get()
      logger.debug(s"Published fulfillment event: ${event.eventType} for shipment ${event.shipmentId}")
    }.recover { case e: Exception =>
      logger.error(s"Failed to publish fulfillment event: ${event.eventType}", e)
      throw new RuntimeException(s"Failed to publish fulfillment event: ${event.eventType}: ${e.getMessage}", e)
    }
  }
}
