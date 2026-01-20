package controllers

import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import javax.inject.Inject
import kafka.CleanupCommandConsumer

class HealthController @Inject()(
  cc: ControllerComponents,
  cleanupConsumer: CleanupCommandConsumer  // Inject to ensure consumer is instantiated
) extends AbstractController(cc) {

  def health: Action[AnyContent] = Action {
    Ok(Json.obj("status" -> "healthy", "service" -> "cart-service"))
  }
}

