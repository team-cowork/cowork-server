package com.cowork.applet.global.surreal

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.http.Status.{OK, UNAUTHORIZED}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.{WSClient, WSResponse, writeableOf_JsValue}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

final class SurrealQueryException(message: String, val transient: Boolean = false) extends RuntimeException(message)

@Singleton
class SurrealClient @Inject() (ws: WSClient, configuration: Configuration)(using ExecutionContext):

  private val baseUrl = s"http://${configuration.get[String]("db.host")}:${configuration.get[Int]("db.port")}"
  private val database = configuration.get[String]("db.name")
  private val username = configuration.get[String]("db.username")
  private val password = configuration.get[String]("db.password")

  // Basic authentication verifies the password hash on every query. Concurrent queries share this sign-in.
  private var session: Option[Future[String]] = None

  // RPC binds variables with their JSON types; POST /sql accepts only string query parameters.
  def query(statements: String, variables: JsObject = Json.obj()): Future[Seq[JsValue]] =
    execute(Json.obj("id" -> 1, "method" -> "query", "params" -> Json.arr(statements, variables)), refreshToken = true)

  private def execute(request: JsObject, refreshToken: Boolean): Future[Seq[JsValue]] =
    val token = currentToken()
    token.flatMap(send(request, _)).flatMap { response =>
      if response.status == UNAUTHORIZED && refreshToken then
        discard(token)
        execute(request, refreshToken = false)
      else Future.successful(SurrealClient.results(response))
    }

  private def send(request: JsObject, token: String): Future[WSResponse] =
    ws.url(s"$baseUrl/rpc")
      .withHttpHeaders(
        "Accept" -> "application/json",
        "Authorization" -> s"Bearer $token",
        "Surreal-NS" -> SurrealClient.Namespace,
        "Surreal-DB" -> database
      )
      .withRequestTimeout(10.seconds)
      .post(request: JsValue)

  private def currentToken(): Future[String] = synchronized {
    session.getOrElse {
      val signIn = requestToken()
      signIn.failed.foreach(_ => discard(signIn))
      session = Some(signIn)
      signIn
    }
  }

  private def discard(stale: Future[String]): Unit = synchronized {
    if session.contains(stale) then session = None
  }

  private def requestToken(): Future[String] =
    val credentials = Json.obj("ns" -> SurrealClient.Namespace, "db" -> database, "user" -> username, "pass" -> password)
    ws.url(s"$baseUrl/signin")
      .withHttpHeaders("Accept" -> "application/json")
      .withRequestTimeout(10.seconds)
      .post(credentials: JsValue)
      .map(SurrealClient.token)

object SurrealClient:
  private val Namespace = "cowork"

  private def requireOk(response: WSResponse): Unit =
    if response.status != OK then
      throw SurrealQueryException(s"SurrealDB returned HTTP ${response.status}.", transient = response.status >= 500)

  private def token(response: WSResponse): String =
    requireOk(response)
    (response.json \ "token").as[String]

  private def results(response: WSResponse): Seq[JsValue] =
    requireOk(response)
    val body = response.json
    (body \ "error" \ "message").asOpt[String].foreach(message => throw SurrealQueryException(message))
    (body \ "result").as[JsArray].value.toSeq.map { statement =>
      if (statement \ "status").as[String] != "OK" then throw SurrealQueryException((statement \ "result").as[String])
      (statement \ "result").as[JsValue]
    }
