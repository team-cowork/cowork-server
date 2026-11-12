package com.cowork.preference.domain.access.model

import io.vertx.core.json.JsonObject

data class InitialChannelRoleOverwrite(val roleId: Long, val permissions: JsonObject)
