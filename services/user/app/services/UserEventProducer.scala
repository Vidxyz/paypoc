package services

import models.UserCreatedEvent
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import play.api.Configuration
import play.api.libs.json.Json
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import javax.inject.{Inject, Singleton}
import java.util.Properties

@Singleton
class UserEventProducer @Inject()(
  config: Configuration
)(implicit ec: ExecutionContext) {

  private val bootstrapServers = config.get[String]("kafka.bootstrap.servers")
  private val topic = config.get[String]("kafka.topics.user.events")

  private lazy val producer: KafkaProducer[String, String] = {
    val props = new Properties()
    props.put("bootstrap.servers", bootstrapServers)
    props.put("key.serializer", classOf[StringSerializer].getName)
    props.put("value.serializer", classOf[StringSerializer].getName)
    props.put("acks", "all")
    props.put("retries", "3")
    new KafkaProducer[String, String](props)
  }

  def publishUserCreatedEvent(event: UserCreatedEvent): Future[Unit] = {
    Future {
      val json = Json.toJson(event).toString()
      val record = new ProducerRecord[String, String](topic, event.userId.toString, json)
      Try {
        producer.send(record).get()
      }.recover { case e: Exception =>
        throw new RuntimeException(s"Failed to publish user.created event: ${e.getMessage}", e)
      }
    }
  }
}
