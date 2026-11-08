package com.cowork.applet.global.surreal

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.http.Status.OK
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.{WSAuthScheme, WSClient, WSResponse, writeableOf_JsValue}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

final class SurrealQueryException(message: String) extends RuntimeException(message)

@Singleton
class SurrealClient @Inject() (ws: WSClient, configuration: Configuration)(using ExecutionContext):

  private val endpoint = s"http://${configuration.get[String]("db.host")}:${configuration.get[Int]("db.port")}/rpc"
  private val database = configuration.get[String]("db.name")
  private val username = configuration.get[String]("db.username")
  private val password = configuration.get[String]("db.password")

  // RPC binds variables with their JSON types; POST /sql accepts only string query parameters.
  def query(statements: String, variables: JsObject = Json.obj()): Future[Seq[JsValue]] =
    ws.url(endpoint)
      .withAuth(username, password, WSAuthScheme.BASIC)
      .withHttpHeaders(
        "Accept" -> "application/json",
        "Surreal-NS" -> SurrealClient.Namespace,
        "Surreal-DB" -> database,
        "Surreal-Auth-NS" -> SurrealClient.Namespace,
        "Surreal-Auth-DB" -> database
      )
      .withRequestTimeout(10.seconds)
      .post(Json.obj("id" -> 1, "method" -> "query", "params" -> Json.arr(statements, variables)): JsValue)
      .map(SurrealClient.results)

object SurrealClient:
  private val Namespace = "cowork"

  private def results(response: WSResponse): Seq[JsValue] =
    if response.status != OK then throw SurrealQueryException(s"SurrealDB returned HTTP ${response.status}.")
    val body = response.json
    (body \ "error" \ "message").asOpt[String].foreach(message => throw SurrealQueryException(message))
    (body \ "result").as[JsArray].value.toSeq.map { statement =>
      if (statement \ "status").as[String] != "OK" then throw SurrealQueryException((statement \ "result").as[String])
      (statement \ "result").as[JsValue]
    }
