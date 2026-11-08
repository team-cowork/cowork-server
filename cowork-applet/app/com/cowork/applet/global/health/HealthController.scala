package com.cowork.applet.global.health

import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.Inject
import play.api.Logger
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

class HealthController @Inject() (components: ControllerComponents, surreal: SurrealClient)(using ExecutionContext)
    extends AbstractController(components):

  private val logger = Logger(getClass)

  def ready: Action[AnyContent] = Action.async {
    surreal.query("RETURN 1;").map(_ => Ok).recover { case NonFatal(error) =>
      logger.warn("Failed the database readiness check.", error)
      ServiceUnavailable
    }
  }
