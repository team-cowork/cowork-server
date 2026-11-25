package com.cowork.applet.global.surreal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper
import java.lang.{Boolean as JavaBoolean, Double as JavaDouble, Long as JavaLong}
import java.util.{ArrayList as JavaArrayList, Base64, LinkedHashMap as JavaLinkedHashMap}
import javax.inject.{Inject, Singleton}
import org.apache.pekko.util.ByteString
import play.api.Configuration
import play.api.http.Status.{OK, UNAUTHORIZED}
import play.api.libs.json.{JsArray, JsBoolean, JsNull, JsNumber, JsObject, JsString, JsValue, Json}
import play.api.libs.ws.{BodyWritable, InMemoryBody, WSClient, WSResponse, writeableOf_JsValue}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

final class SurrealQueryException(message: String, val transient: Boolean = false) extends RuntimeException(message)

@Singleton
class SurrealClient @Inject() (ws: WSClient, configuration: Configuration)(using ExecutionContext):

  private val baseUrl = s"http://${configuration.get[String]("db.host")}:${configuration.get[Int]("db.port")}"
  private val database = configuration.get[String]("db.name")
  private val username = configuration.get[String]("db.username")
  private val password = configuration.get[String]("db.password")

  // Basic authentication verifies the password hash on every query. Concurrent queries share this sign-in.
  private var session: Option[Future[String]] = None

  // RPC binds variables with their own types; POST /sql accepts only string query parameters.
  // RPC uses CBOR because the JSON format turns strings that look like record IDs, datetimes, or UUIDs into
  // those types, which would change stored user data such as storage keys and values.
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
        "Accept" -> SurrealClient.Cbor,
        "Authorization" -> s"Bearer $token",
        "Surreal-NS" -> SurrealClient.Namespace,
        "Surreal-DB" -> database
      )
      .withRequestTimeout(10.seconds)
      .post(SurrealClient.cbor.writeValueAsBytes(SurrealClient.plain(request)))(using SurrealClient.cborBody)

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
  private val Cbor = "application/cbor"

  private val cbor = CBORMapper()
  private val cborBody: BodyWritable[Array[Byte]] = BodyWritable(bytes => InMemoryBody(ByteString(bytes)), Cbor)

  private def requireOk(response: WSResponse): Unit =
    if response.status != OK then
      throw SurrealQueryException(s"SurrealDB returned HTTP ${response.status}.", transient = response.status >= 500)

  private def token(response: WSResponse): String =
    requireOk(response)
    (response.json \ "token").as[String]

  // play-json keeps every number as BigDecimal, which CBOR would send as a decimal. Integers go as integers
  // and other numbers as floats, the same types the JSON format produced.
  private def plain(value: JsValue): AnyRef = value match
    case JsNull                                 => null
    case JsBoolean(flag)                        => JavaBoolean.valueOf(flag)
    case JsString(text)                         => text
    case JsNumber(number) if number.isValidLong => JavaLong.valueOf(number.toLong)
    case JsNumber(number)                       => JavaDouble.valueOf(number.toDouble)
    case JsArray(items)                         => JavaArrayList[AnyRef](items.map(plain).asJava)
    case JsObject(fields) =>
      val map = JavaLinkedHashMap[String, AnyRef]()
      fields.foreach((key, item) => map.put(key, plain(item)))
      map

  // Stored fields are plain values. CBOR tags that SurrealDB adds, such as record IDs, are read as their content.
  private def json(node: JsonNode): JsValue =
    if node == null || node.isNull || node.isMissingNode then JsNull
    else if node.isBoolean then JsBoolean(node.booleanValue)
    else if node.isIntegralNumber then JsNumber(BigDecimal(node.bigIntegerValue))
    // Aggregates such as math::max over no rows return an infinite float, which JSON cannot hold.
    else if node.isFloatingPointNumber && !JavaDouble.isFinite(node.doubleValue) then JsNull
    else if node.isNumber then JsNumber(BigDecimal(node.decimalValue))
    else if node.isTextual then JsString(node.textValue)
    else if node.isBinary then JsString(Base64.getEncoder.encodeToString(node.binaryValue))
    else if node.isArray then JsArray(node.elements.asScala.map(json).toSeq)
    else JsObject(node.fields.asScala.map(entry => entry.getKey -> json(entry.getValue)).toSeq)

  private def results(response: WSResponse): Seq[JsValue] =
    requireOk(response)
    val body = json(cbor.readTree(response.bodyAsBytes.toArray))
    (body \ "error" \ "message").asOpt[String].foreach(message => throw SurrealQueryException(message))
    (body \ "result").as[JsArray].value.toSeq.map { statement =>
      if (statement \ "status").as[String] != "OK" then throw SurrealQueryException((statement \ "result").as[String])
      (statement \ "result").as[JsValue]
    }
