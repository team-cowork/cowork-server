package com.cowork.preference.domain.projectrole.model

import io.vertx.core.json.JsonObject

data class ProjectRoleDefinition(
    val projectId: Long,
    val roleName: String,
    val permissions: JsonObject,
    val priority: Int = 0,
)
