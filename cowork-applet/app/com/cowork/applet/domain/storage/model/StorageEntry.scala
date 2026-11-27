package com.cowork.applet.domain.storage.model

import java.time.Instant
import play.api.libs.json.{JsValue, Json, OFormat}

// Each team that installed the applet has its own storage. Within it, ownerId is the user ID for USER entries and
// empty for APPLET entries, so one unique index covers both scopes.
final case class StorageEntry(
    appletId: String,
    teamId: Long,
    scope: StorageScope,
    ownerId: String,
    key: String,
    value: JsValue,
    updatedAt: Instant
)

object StorageEntry:
  given OFormat[StorageEntry] = Json.format[StorageEntry]

  def ownerOf(scope: StorageScope, userId: String): String = scope match
    case StorageScope.USER   => userId
    case StorageScope.APPLET => ""
