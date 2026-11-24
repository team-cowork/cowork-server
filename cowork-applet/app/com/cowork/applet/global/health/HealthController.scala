package com.cowork.applet.global.health

import com.cowork.applet.domain.team.projection.TeamProjectionReadiness
import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.Inject
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

class HealthController @Inject() (
    components: ControllerComponents,
    surreal: SurrealClient,
    teamProjectionReadiness: TeamProjectionReadiness
)(using ExecutionContext)
    extends AbstractController(components):

  private val logger = Logger(getClass)

  // Liveness for the public Gateway probe. JSON lets the probe tell a downstream response apart.
  def live: Action[AnyContent] = Action(Ok(Json.obj("status" -> "UP")))

  // Kafka projection rules hold service readiness until the team projections reach the broker high-watermarks.
  def ready: Action[AnyContent] = Action.async {
    surreal.query("RETURN 1;").map(_ => if teamProjectionReadiness.isReady then Ok else ServiceUnavailable).recover { case NonFatal(error) =>
      logger.warn("Failed the database readiness check.", error)
      ServiceUnavailable
    }
  }
