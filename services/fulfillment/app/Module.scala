package app

import com.google.inject.AbstractModule
import play.api.{Configuration, Environment}

class Module(environment: Environment, configuration: Configuration) extends AbstractModule {
  override def configure(): Unit = {
    // No eager singletons needed for now
    // Future: Add Kafka consumers if needed
  }
}
