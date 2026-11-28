package com.cowork.applet.global.error

import javax.inject.{Inject, Singleton}
import play.api.{Environment, OptionalSourceMapper}
import play.api.http.JsonHttpErrorHandler
import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Result, Results}
import scala.annotation.tailrec
import scala.concurrent.Future

@Singleton
class ApiErrorHandler @Inject() (environment: Environment, optionalSourceMapper: OptionalSourceMapper)
    extends JsonHttpErrorHandler(environment, optionalSourceMapper.sourceMapper):

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    ApiErrorHandler.expected(exception) match
      case Some(failure) => Future.successful(Results.Status(failure.status)(Json.obj("message" -> failure.getMessage)))
      case None          => super.onServerError(request, exception)

object ApiErrorHandler:
  // Play and Future combinators can wrap the original failure, so search the cause chain.
  @tailrec
  private def expected(error: Throwable): Option[ExpectedException] = error match
    case null                       => None
    case failure: ExpectedException => Some(failure)
    case other                      => expected(other.getCause)
