package controllers

import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import javax.inject.Inject

class HealthController @Inject()(
  cc: ControllerComponents
) extends AbstractController(cc) {

  def health: Action[AnyContent] = Action {
    Ok(Json.obj("status" -> "healthy", "service" -> "user-service"))
  }
}
