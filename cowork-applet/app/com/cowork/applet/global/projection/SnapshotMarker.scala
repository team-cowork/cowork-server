package com.cowork.applet.global.projection

import java.time.Instant
import java.util.UUID
import play.api.libs.json.{JsObject, Json}
import scala.util.Try

// Producers append one completion marker per partition after each full snapshot.
object SnapshotMarker:
  val KeyPrefix = "__cowork_projection_snapshot_complete__:"
  private val EventType = "PROJECTION_SNAPSHOT_COMPLETED"

  def isReserved(key: String): Boolean = key != null && key.startsWith(KeyPrefix)

  // Returns the snapshot ID, or the reason the marker is invalid.
  def parse(topic: String, partition: Int, key: String, value: String, expectedSource: String): Either[String, String] =
    if key != s"$KeyPrefix$partition" then Left("projection snapshot marker key does not match its partition")
    else
      Try(Json.parse(value).as[JsObject]).toOption match
        case None => Left("projection snapshot marker JSON is invalid")
        case Some(payload) =>
          val text = (field: String) => (payload \ field).asOpt[String]
          if !text("eventType").contains(EventType) then Left("projection snapshot marker eventType is invalid")
          else if !text("topic").contains(topic) then Left("projection snapshot marker topic does not match the record")
          else if !(payload \ "partition").asOpt[Int].contains(partition) then
            Left("projection snapshot marker partition does not match the record")
          else if text("occurredAt").flatMap(v => Try(Instant.parse(v)).toOption).isEmpty then
            Left("projection snapshot marker occurredAt is invalid")
          else if !text("source").contains(expectedSource) then
            Left(s"projection snapshot marker source does not match the expected producer: $expectedSource")
          else
            text("snapshotId").flatMap(v => Try(UUID.fromString(v).toString).toOption)
              .toRight("projection snapshot marker snapshotId is invalid")
