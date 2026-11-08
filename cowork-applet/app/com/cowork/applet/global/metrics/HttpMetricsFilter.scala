package com.cowork.applet.global.metrics

import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import javax.inject.Inject
import org.apache.pekko.stream.Materializer
import play.api.mvc.{Filter, RequestHeader, Result}
import play.api.routing.Router
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

// Uses the Micrometer server metric name so the shared overview dashboards and alerts include this service.
class HttpMetricsFilter @Inject() (registry: PrometheusMeterRegistry)(using
    override val mat: Materializer,
    executionContext: ExecutionContext
) extends Filter:

  override def apply(next: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] =
    if HttpMetricsFilter.isOperational(request.path) then next(request)
    else
      val sample = Timer.start(registry)
      next(request).andThen {
        case Success(result) => record(sample, request, result.header.status)
        case Failure(_)      => record(sample, request, 500)
      }

  private def record(sample: Timer.Sample, request: RequestHeader, status: Int): Unit =
    val uri = request.attrs.get(Router.Attrs.HandlerDef).map(handler => HttpMetricsFilter.uriTemplate(handler.path))
    sample.stop(
      Timer
        .builder("http.server.requests")
        .publishPercentileHistogram()
        .tags("method", request.method, "uri", uri.getOrElse("UNKNOWN"), "status", status.toString)
        .register(registry)
    )

object HttpMetricsFilter:
  private val PathParameter = """\$(\w+)<[^>]*>""".r

  private def isOperational(path: String): Boolean = path.startsWith("/health") || path == "/metrics"

  // Play stores routes as /applets/$id<[^/]+>; the raw form would add regex text to the metric label.
  private def uriTemplate(path: String): String = PathParameter.replaceAllIn(path, "{$1}")
