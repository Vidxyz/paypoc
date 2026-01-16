package services

import com.redis.RedisClient
import play.api.Configuration
import play.api.Logger
import play.api.libs.json.{Json, Reads, Writes}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

@Singleton
class RedisService @Inject()(config: Configuration) {
  private val logger = Logger(getClass)
  
  private val host = config.get[String]("redis.host")
  private val port = config.get[Int]("redis.port")
  private val password = config.getOptional[String]("redis.password")
  private val database = config.get[Int]("redis.database")
  private val timeout = config.get[Int]("redis.timeout")
  
  private lazy val redisClient: RedisClient = {
    try {
      val client = password match {
        case Some(pwd) => new RedisClient(host, port, secret = Some(pwd), database = database)
        case None => new RedisClient(host, port, database = database)
      }
      logger.info(s"Redis client initialized: $host:$port (database: $database)")
      client
    } catch {
      case e: Exception =>
        logger.error(s"Failed to initialize Redis client: $host:$port", e)
        throw e
    }
  }
  
  def get[T](key: String)(implicit reads: Reads[T]): Option[T] = {
    Try {
      redisClient.get(key).flatMap { jsonStr =>
        Try(Json.parse(jsonStr).as[T]).toOption
      }
    } match {
      case Success(value) => value
      case Failure(e) =>
        logger.error(s"Error getting key $key from Redis", e)
        None
    }
  }
  
  def set[T](key: String, value: T, ttlSeconds: Option[Int] = None)(implicit writes: Writes[T]): Boolean = {
    Try {
      val jsonStr = Json.toJson(value).toString()
      ttlSeconds match {
        case Some(ttl) => redisClient.setex(key, ttl, jsonStr)
        case None => redisClient.set(key, jsonStr)
      }
    } match {
      case Success(result) => result
      case Failure(e) =>
        logger.error(s"Error setting key $key in Redis", e)
        false
    }
  }
  
  def delete(key: String): Boolean = {
    Try {
      redisClient.del(key).exists(_ > 0)
    } match {
      case Success(result) => result
      case Failure(e) =>
        logger.error(s"Error deleting key $key from Redis", e)
        false
    }
  }
  
  def exists(key: String): Boolean = {
    Try {
      redisClient.exists(key)
    } match {
      case Success(result) => result
      case Failure(e) =>
        logger.error(s"Error checking existence of key $key in Redis", e)
        false
    }
  }
  
  def expire(key: String, seconds: Int): Boolean = {
    Try {
      redisClient.expire(key, seconds)
    } match {
      case Success(result) => result
      case Failure(e) =>
        logger.error(s"Error setting expiry for key $key in Redis", e)
        false
    }
  }
  
  def close(): Unit = {
    Try {
      redisClient.close()
    } match {
      case Success(_) => logger.info("Redis client closed")
      case Failure(e) => logger.error("Error closing Redis client", e)
    }
  }
}

