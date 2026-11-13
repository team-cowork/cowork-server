package com.cowork.applet.global.swagger

import com.iheart.playSwagger.OutputTransformer
import play.api.libs.json.{JsArray, JsBoolean, JsNull, JsObject, JsString, JsValue, Json}
import scala.util.Try

// sbt-play-swagger writes the OpenAPI 3.0 keyword `nullable`, which OpenAPI 3.1 and later do not define.
// This transformer replaces it with JSON Schema 2020-12 null types so that the document stays valid OpenAPI 3.2.
// Only the swagger build task loads this class; the application never loads it at runtime.
// TODO: Delete this transformer, its swaggerOutputTransformers entry, and the Provided play-swagger dependency in
//  build.sbt when sbt-play-swagger generates OpenAPI 3.2 natively. Until then, add a rule here for each new
//  OpenAPI 3.0-only keyword that sbt-play-swagger writes.
final class OpenApi32Transformer extends OutputTransformer:

  def apply(document: JsObject): Try[JsObject] = Try(convert(document))

  private def convert(obj: JsObject): JsObject =
    val converted = JsObject(obj.fields.map((key, value) => key -> convert(value)))
    converted.value.get("nullable") match
      case Some(JsBoolean(true)) => allowNull(converted - "nullable")
      case Some(JsBoolean(false)) => converted - "nullable"
      case _ => converted

  private def convert(value: JsValue): JsValue = value match
    case obj: JsObject => convert(obj)
    case JsArray(items) => JsArray(items.map(convert))
    case other => other

  private def allowNull(schema: JsObject): JsObject =
    (schema.value.get("$ref"), schema.value.get("type")) match
      case (Some(ref), _) =>
        schema - "$ref" + ("anyOf" -> Json.arr(Json.obj("$ref" -> ref), Json.obj("type" -> "null")))
      case (_, Some(JsString(tpe))) => withNullEnum(schema + ("type" -> Json.arr(tpe, "null")))
      case (_, Some(JsArray(types))) if !types.contains(JsString("null")) =>
        withNullEnum(schema + ("type" -> JsArray(types :+ JsString("null"))))
      case _ => schema

  private def withNullEnum(schema: JsObject): JsObject = schema.value.get("enum") match
    case Some(JsArray(values)) if !values.contains(JsNull) => schema + ("enum" -> JsArray(values :+ JsNull))
    case _ => schema
