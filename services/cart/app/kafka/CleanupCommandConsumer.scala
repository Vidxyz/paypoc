package kafka

import play.api.Configuration
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, Json}
import services.CartService
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import javax.inject.{Inject, Singleton}
import java.util.Properties
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Collections

@Singleton
class CleanupCommandConsumer @Inject()(
  config: Configuration,
  cartService: CartService,
  lifecycle: ApplicationLifecycle
)(implicit ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  
  // Log immediately when the consumer is instantiated
  logger.info("=== CleanupCommandConsumer: Constructor called - initializing Kafka consumer ===")
  
  private val bootstrapServers = config.get[String]("kafka.bootstrap.servers")
  private val topic = config.getOptional[String]("kafka.topics.cart.commands").getOrElse("cart.commands")
  private val groupId = "cart-service-cleanup"
  
  logger.info(s"=== CleanupCommandConsumer: Configuration loaded ===")
  logger.info(s"  Bootstrap servers: $bootstrapServers")
  logger.info(s"  Topic: $topic")
  logger.info(s"  Consumer group ID: $groupId")
  
  private val consumer: KafkaConsumer[String, String] = {
    logger.info("=== CleanupCommandConsumer: Creating KafkaConsumer instance ===")
    try {
      val props = new Properties()
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
      props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
      val kafkaConsumer = new KafkaConsumer[String, String](props)
      logger.info("=== CleanupCommandConsumer: KafkaConsumer instance created successfully ===")
      kafkaConsumer
    } catch {
      case e: Exception =>
        logger.error("=== CleanupCommandConsumer: Failed to create KafkaConsumer ===", e)
        throw e
    }
  }
  
  // Start consumer on application startup
  private val consumerThread = new Thread(() => {
    logger.info(s"=== Starting cart cleanup command consumer ===")
    logger.info(s"Kafka bootstrap servers: $bootstrapServers")
    logger.info(s"Topic: $topic")
    logger.info(s"Consumer group ID: $groupId")
    
    var subscribed = false
    try {
      logger.info(s"Attempting to subscribe to topic: $topic")
      consumer.subscribe(Collections.singletonList(topic))
      logger.info(s"✓ Successfully subscribed to topic: $topic")
      subscribed = true
    } catch {
      case e: org.apache.kafka.common.errors.TimeoutException =>
        logger.error(s"✗ Timeout while subscribing to topic $topic - Kafka broker may be unavailable", e)
        logger.error(s"  Bootstrap servers: $bootstrapServers")
        subscribed = false
      case e: org.apache.kafka.common.errors.LeaderNotAvailableException =>
        logger.error(s"✗ Leader not available for topic $topic - topic may not exist or broker is not ready", e)
        logger.error(s"  The topic will be created automatically when first message is published")
        logger.error(s"  Retrying subscription in 5 seconds...")
        Thread.sleep(5000)
        try {
          consumer.subscribe(Collections.singletonList(topic))
          logger.info(s"✓ Successfully subscribed to topic: $topic after retry")
          subscribed = true
        } catch {
          case retryE: Exception =>
            logger.error(s"✗ Failed to subscribe after retry", retryE)
            subscribed = false
        }
      case e: Exception =>
        logger.error(s"✗ Failed to subscribe to topic $topic", e)
        logger.error(s"  Error type: ${e.getClass.getName}")
        logger.error(s"  Error message: ${e.getMessage}")
        subscribed = false
    }
    
    if (!subscribed) {
      logger.error("Cannot start consumer loop - subscription failed")
    } else {
      var pollCount = 0
      var lastHeartbeat = System.currentTimeMillis()
      val heartbeatInterval = 60000L // Log heartbeat every 60 seconds
      
      while (!Thread.currentThread().isInterrupted) {
        try {
          val records = consumer.poll(Duration.ofMillis(1000))
          pollCount += 1
          
          val recordCount = records.count()
          if (recordCount > 0) {
            logger.info(s"Polled $recordCount message(s) from topic $topic (total polls: $pollCount)")
          } else if (pollCount % 60 == 0) {
            // Log every 60 polls (approximately every minute) when no messages
            logger.debug(s"Polled topic $topic - no messages (total polls: $pollCount)")
          }
          
          records.forEach { record =>
            logger.info(s"Processing message from topic $topic: partition=${record.partition()}, offset=${record.offset()}, key=${record.key()}")
            processCommand(record.key(), record.value())
          }
          
          // Periodic heartbeat to show consumer is alive
          val now = System.currentTimeMillis()
          if (now - lastHeartbeat >= heartbeatInterval) {
            logger.info(s"Cart cleanup consumer heartbeat - still listening on topic $topic (polls: $pollCount)")
            lastHeartbeat = now
          }
        } catch {
          case e: InterruptedException =>
            logger.info("Cart cleanup consumer thread interrupted, stopping")
            Thread.currentThread().interrupt()
          case e: Exception =>
            logger.error(s"Error consuming cleanup commands from topic $topic", e)
        }
      }
      logger.info("Cart cleanup consumer thread stopped")
    }
  }, "cart-cleanup-consumer")
  
  consumerThread.setDaemon(true)
  
  // Start consumer thread immediately when class is instantiated
  logger.info("=== CleanupCommandConsumer: Starting consumer thread ===")
  consumerThread.start()
  logger.info("=== CleanupCommandConsumer: Consumer thread started successfully ===")
  
  // Stop consumer on application shutdown
  lifecycle.addStopHook { () =>
    Future {
      logger.info("=== CleanupCommandConsumer: Stopping cart cleanup command consumer ===")
      consumerThread.interrupt()
      try {
        consumerThread.join(5000) // Wait up to 5 seconds for thread to stop
        logger.info("=== CleanupCommandConsumer: Consumer thread stopped ===")
      } catch {
        case e: InterruptedException =>
          logger.warn("Interrupted while waiting for consumer thread to stop")
      }
      try {
        consumer.close()
        logger.info("=== CleanupCommandConsumer: KafkaConsumer closed ===")
      } catch {
        case e: Exception =>
          logger.error("Error closing KafkaConsumer", e)
      }
    }
  }
  
  private def processCommand(key: String, value: String): Unit = {
    logger.info(s"=== Received Cleanup Command ===")
    logger.info(s"Topic: $topic")
    logger.info(s"Key: $key")
    logger.info(s"Value: $value")
    
    Try {
      val json = Json.parse(value).as[JsObject]
      val commandType = (json \ "type").asOpt[String]
      
      logger.info(s"Parsed command type: $commandType")
      logger.info(s"Full command JSON: ${Json.prettyPrint(json)}")
      
      commandType match {
        case Some("CLEANUP_ABANDONED_CARTS") =>
          logger.info("=== Starting cleanup of abandoned carts ===")
          val startTime = System.currentTimeMillis()
          val result = cartService.cleanupAbandonedCarts()
          result.onComplete {
            case Success(cleanupResult) =>
              val duration = System.currentTimeMillis() - startTime
              logger.info(s"=== Cleanup completed successfully ===")
              logger.info(s"Duration: ${duration}ms")
              logger.info(s"Found carts: ${cleanupResult.foundCount}")
              logger.info(s"Processed carts: ${cleanupResult.processedCount}")
              logger.info(s"Errors: ${cleanupResult.errorCount}")
              if (cleanupResult.foundCount > 0) {
                logger.info(s"Cleanup summary: ${cleanupResult.processedCount}/${cleanupResult.foundCount} carts processed successfully, ${cleanupResult.errorCount} errors")
              } else {
                logger.info("No abandoned carts found to cleanup")
              }
            case Failure(e) =>
              val duration = System.currentTimeMillis() - startTime
              logger.error(s"=== Cleanup failed after ${duration}ms ===", e)
              logger.error(s"Error message: ${e.getMessage}")
              logger.error(s"Error stack trace: ${e.getStackTrace.mkString("\n")}")
          }
        case Some(otherType) =>
          logger.warn(s"Unknown cleanup command type: $otherType")
          logger.warn(s"Expected: CLEANUP_ABANDONED_CARTS")
        case None =>
          logger.warn("Cleanup command missing 'type' field")
          logger.warn(s"Command JSON: ${Json.prettyPrint(json)}")
      }
    }.recover { case e: Exception =>
      logger.error(s"=== Error processing cleanup command ===")
      logger.error(s"Key: $key")
      logger.error(s"Value: $value")
      logger.error(s"Error: ${e.getMessage}", e)
      logger.error(s"Stack trace: ${e.getStackTrace.mkString("\n")}")
    }
  }
}
