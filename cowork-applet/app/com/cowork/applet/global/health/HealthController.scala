package com.cowork.applet.global.health

import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.Inject
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

class HealthController @Inject() (components: ControllerComponents, surreal: SurrealClient)(using ExecutionContext)
    extends AbstractController(components):

  private val logger = Logger(getClass)

  // Liveness for the public Gateway probe. JSON lets the probe tell a downstream response apart.
  def live: Action[AnyContent] = Action(Ok(Json.obj("status" -> "UP")))

  def ready: Action[AnyContent] = Action.async {
    surreal.query("RETURN 1;").map(_ => Ok).recover { case NonFatal(error) =>
      logger.warn("Failed the database readiness check.", error)
      ServiceUnavailable
    }
  }
