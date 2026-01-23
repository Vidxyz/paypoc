package controllers

import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import javax.inject.Inject
import scala.io.Source
import java.io.File

class ApiDocsController @Inject()(
  cc: ControllerComponents
) extends AbstractController(cc) {

  def swaggerUI: Action[AnyContent] = Action {
    val html = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Fulfillment Service API Documentation</title>
  <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-dist@5.9.0/swagger-ui.css" />
  <style>
    html {
      box-sizing: border-box;
      overflow: -moz-scrollbars-vertical;
      overflow-y: scroll;
    }
    *, *:before, *:after {
      box-sizing: inherit;
    }
    body {
      margin:0;
      background: #fafafa;
    }
  </style>
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@5.9.0/swagger-ui-bundle.js"></script>
  <script src="https://unpkg.com/swagger-ui-dist@5.9.0/swagger-ui-standalone-preset.js"></script>
  <script>
    window.onload = function() {
      const ui = SwaggerUIBundle({
        url: "/api-docs/openapi.json",
        dom_id: '#swagger-ui',
        deepLinking: true,
        presets: [
          SwaggerUIBundle.presets.apis,
          SwaggerUIStandalonePreset
        ],
        plugins: [
          SwaggerUIBundle.plugins.DownloadUrl
        ],
        layout: "StandaloneLayout"
      });
    };
  </script>
</body>
</html>"""
    Ok(html).as("text/html")
  }

  def favicon: Action[AnyContent] = Action {
    NoContent
  }

  def openApiJson: Action[AnyContent] = Action {
    try {
      val swaggerJson = (
        Option(getClass.getClassLoader.getResourceAsStream("public/swagger.json"))
          .orElse(Option(getClass.getClassLoader.getResourceAsStream("swagger.json")))
          .orElse {
            val file1 = new File("public/swagger.json")
            if (file1.exists() && file1.isFile) Some(new java.io.FileInputStream(file1)) else None
          }
          .orElse {
            val file2 = new File("swagger.json")
            if (file2.exists() && file2.isFile) Some(new java.io.FileInputStream(file2)) else None
          }
      ).map { stream =>
        try {
          Source.fromInputStream(stream, "UTF-8").mkString
        } finally {
          stream.close()
        }
      }.getOrElse {
        """{"openapi":"3.0.0","info":{"title":"Fulfillment Service API","version":"1.0.0","description":"Swagger spec not found. Ensure 'sbt swagger' runs during build."},"paths":{}}"""
      }
      
      Ok(swaggerJson).as("application/json")
    } catch {
      case e: Exception =>
        Ok(s"""{"openapi":"3.0.0","info":{"title":"Fulfillment Service API","version":"1.0.0","description":"Error loading swagger.json: ${e.getMessage}"},"paths":{}}""").as("application/json")
    }
  }
}
