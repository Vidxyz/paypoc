package app

import com.google.inject.AbstractModule
import play.api.{Configuration, Environment, Logger}
import kafka.CleanupCommandConsumer

class Module(environment: Environment, configuration: Configuration) extends AbstractModule {
  private val logger = Logger(getClass)
  
  override def configure(): Unit = {
    logger.info("=== Module.configure() called - Registering CleanupCommandConsumer ===")
    logger.info("=== Environment: " + environment.mode + " ===")
    // Eagerly bind CleanupCommandConsumer to ensure it starts on application startup
    // This will instantiate the consumer immediately when the application starts
    bind(classOf[CleanupCommandConsumer]).asEagerSingleton()
    logger.info("=== Module.configure() completed - CleanupCommandConsumer registered ===")
  }
}
