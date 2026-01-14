package controllers

import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import javax.inject.Inject

class ApiDocsController @Inject()(
  cc: ControllerComponents
) extends AbstractController(cc) {

  def swaggerUI: Action[AnyContent] = Action {
    Redirect("/assets/swagger-ui/index.html?url=/api-docs/openapi.json")
  }

  def openApiJson: Action[AnyContent] = Action {
    Ok(play.api.libs.json.Json.parse(scala.io.Source.fromFile("public/swagger.json").mkString))
      .as("application/json")
  }

  def favicon: Action[AnyContent] = Action {
    NoContent
  }
}

